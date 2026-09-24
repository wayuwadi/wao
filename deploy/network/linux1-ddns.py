#!/usr/bin/env python3
"""Update linux1.wayuwa.cn A and AAAA records through Alibaba Cloud DNS."""

import base64
import datetime as dt
import hashlib
import hmac
import ipaddress
import json
import os
import shlex
import subprocess
import sys
import urllib.parse
import urllib.request
import uuid

API_ENDPOINT = "https://alidns.aliyuncs.com/"
DOMAIN = "wayuwa.cn"
RR = "linux1"
TTL = "600"
CREDENTIALS = os.path.expanduser("~/.config/linux1-ddns/credentials")


def load_credentials():
    values = {}
    with open(CREDENTIALS, encoding="utf-8") as handle:
        for raw_line in handle:
            line = raw_line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            values[key.strip()] = shlex.split(value.strip())[0]
    return (
        values["ALIBABA_CLOUD_ACCESS_KEY_ID"],
        values["ALIBABA_CLOUD_ACCESS_KEY_SECRET"],
        values.get("FEISHU_WEBHOOK_URL"),
    )


def encode(value):
    return urllib.parse.quote(str(value), safe="~")


def api(action, access_key_id, access_key_secret, **parameters):
    request = {
        "AccessKeyId": access_key_id,
        "Action": action,
        "Format": "JSON",
        "SignatureMethod": "HMAC-SHA1",
        "SignatureNonce": str(uuid.uuid4()),
        "SignatureVersion": "1.0",
        "Timestamp": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "Version": "2015-01-09",
        **parameters,
    }
    canonical = "&".join(f"{encode(key)}={encode(request[key])}" for key in sorted(request))
    string_to_sign = f"GET&%2F&{encode(canonical)}"
    signature = base64.b64encode(
        hmac.new(f"{access_key_secret}&".encode(), string_to_sign.encode(), hashlib.sha1).digest()
    ).decode()
    request["Signature"] = signature
    url = API_ENDPOINT + "?" + urllib.parse.urlencode(request)
    try:
        with urllib.request.urlopen(url, timeout=20) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")
        raise RuntimeError(f"AliDNS {action} failed: HTTP {error.code}: {detail}") from error


def preferred_ipv6():
    # The desktop VPN's default route can select a synthetic ULA. Only publish
    # a non-temporary global address assigned to the physical LAN interface.
    result = subprocess.run(
        ["/usr/sbin/ip", "-j", "-6", "addr", "show", "dev",
         os.environ.get("WAO_DDNS_INTERFACE", "enp3s0")],
        check=True, capture_output=True, text=True, timeout=5)
    return select_ipv6(json.loads(result.stdout))


def select_ipv6(interfaces):
    candidates = []
    for interface in interfaces:
        for item in interface.get("addr_info", []):
            address = ipaddress.ip_address(item["local"])
            if (address.version == 6 and address.is_global
                    and item.get("scope") == "global"
                    and not any(item.get(flag) for flag in ("temporary", "tentative", "deprecated", "dadfailed"))
                    and item.get("preferred_life_time", 1) != 0):
                candidates.append((item.get("prefixlen") != 128, str(address)))
    if not candidates:
        raise RuntimeError("No preferred global IPv6 on the configured physical interface")
    return sorted(candidates)[0][1]


def public_ipv4():
    # DDNS must observe this machine's WAN address, never an interactive
    # shell's HTTP proxy exit address (for example a maintenance agent).
    direct_environment = os.environ.copy()
    for variable in (
        "HTTP_PROXY",
        "HTTPS_PROXY",
        "ALL_PROXY",
        "http_proxy",
        "https_proxy",
        "all_proxy",
    ):
        direct_environment.pop(variable, None)
    addresses = []
    interface = os.environ.get("WAO_DDNS_INTERFACE", "enp3s0")
    for host in ("checkip.amazonaws.com", "ipv4.icanhazip.com"):
        try:
            # Resolve outside desktop fake-IP DNS, then bind the physical NIC.
            # HTTPS verifies the original hostname, never the numeric address.
            query = urllib.parse.urlencode({"name": host, "type": "A"})
            with urllib.request.urlopen("https://dns.alidns.com/resolve?" + query, timeout=6) as response:
                dns = json.load(response)
            target = next(str(ipaddress.IPv4Address(row["data"]))
                          for row in dns.get("Answer", [])
                          if row.get("type") == 1 and ipaddress.ip_address(row["data"]).is_global)
            result = subprocess.run(
                ["/usr/bin/curl", "-4", "--noproxy", "*", "--interface", interface,
                 "--resolve", host + ":443:" + target, "-fsS", "--max-time", "8", "https://" + host],
                capture_output=True, text=True, env=direct_environment, timeout=10)
            if result.returncode == 0:
                parsed = ipaddress.IPv4Address(result.stdout.strip())
                if parsed.is_global:
                    addresses.append(str(parsed))
        except (OSError, ValueError, StopIteration, subprocess.SubprocessError):
            continue
    if len(addresses) == 2 and addresses[0] == addresses[1]:
        return addresses[0]
    raise RuntimeError("Unable to confirm matching physical WAN IPv4 observations")


def notify_feishu(webhook_url, ipv4_address, ipv6_address):
    if not webhook_url:
        return
    payload = json.dumps(
        {
            "msg_type": "text",
            "content": {
                "text": (
                    "wage-linux1 DDNS 更新："
                    f"IPv4={ipv4_address}，IPv6={ipv6_address}"
                )
            },
        },
        ensure_ascii=False,
    ).encode("utf-8")
    request = urllib.request.Request(
        webhook_url,
        data=payload,
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        result = json.load(response)
    code = result.get("code", result.get("StatusCode", 0))
    if code != 0:
        raise RuntimeError(f"Feishu webhook failed: {result}")


def sync_record(record_type, address, access_key_id, access_key_secret):
    result = api(
        "DescribeDomainRecords",
        access_key_id,
        access_key_secret,
        DomainName=DOMAIN,
        RRKeyWord=RR,
        TypeKeyWord=record_type,
        SearchMode="COMBINATION",
        PageSize="100",
    )
    records = [
        record
        for record in result.get("DomainRecords", {}).get("Record", [])
        if record.get("RR") == RR
        and record.get("Type") == record_type
        and record.get("Line") == "default"
    ]
    if not records:
        created = api(
            "AddDomainRecord",
            access_key_id,
            access_key_secret,
            DomainName=DOMAIN,
            RR=RR,
            Type=record_type,
            Value=address,
            TTL=TTL,
        )
        print(
            f"created {RR}.{DOMAIN} {record_type} {address} "
            f"record={created.get('RecordId')}"
        )
        return True
    record = records[0]
    if record.get("Value") == address:
        print(f"unchanged {RR}.{DOMAIN} {record_type} {address}")
        return False
    updated = api(
        "UpdateDomainRecord",
        access_key_id,
        access_key_secret,
        RecordId=record["RecordId"],
        RR=RR,
        Type=record_type,
        Value=address,
        TTL=TTL,
    )
    print(
        f"updated {RR}.{DOMAIN} {record_type} {address} "
        f"record={updated.get('RecordId')}"
    )
    return True


def main():
    access_key_id, access_key_secret, webhook_url = load_credentials()
    addresses, changed, failures = {}, False, []
    for record_type, discover in (("AAAA", preferred_ipv6), ("A", public_ipv4)):
        try:
            address = discover()
            changed = sync_record(record_type, address, access_key_id, access_key_secret) or changed
            addresses[record_type] = address
        except Exception:
            # Never include signed URLs, response bodies or credentials in logs.
            failures.append(record_type)
            print(f"ERROR: {record_type} discovery/update failed; existing record preserved", file=sys.stderr)
    if changed:
        notify_feishu(webhook_url, addresses.get("A", "未更新"), addresses.get("AAAA", "未更新"))
    if failures:
        raise RuntimeError("DDNS incomplete: " + ",".join(failures))


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("ERROR: DDNS failed; inspect per-family status", file=sys.stderr)
        raise SystemExit(1)
