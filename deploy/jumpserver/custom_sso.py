"""JumpServer CE Custom SSO adapter for short-lived WAO HMAC tickets."""

import base64
import hashlib
import hmac
import json
import os
import re
import time


_MEMBER_ID = re.compile(r"^[A-Za-z0-9_-]{1,96}$")
_ROLES = {"SystemAdmin", "SystemUser"}
_AUDIENCE = "jumpserver"
_MAX_CLOCK_SKEW_SECONDS = 10


def _b64decode(value):
    padding = "=" * (-len(value) % 4)
    return base64.urlsafe_b64decode((value + padding).encode("ascii"))


def _decode_ticket(token, now=None):
    secret = os.environ.get("JUMPSERVER_SSO_SHARED_SECRET", "")
    if len(secret) < 32:
        return None, "WAO SSO shared secret is unavailable"
    try:
        encoded, signature = token.split(".", 1)
        expected = hmac.new(
            secret.encode("utf-8"), encoded.encode("ascii"), hashlib.sha256
        ).digest()
        supplied = _b64decode(signature)
        if not hmac.compare_digest(expected, supplied):
            return None, "WAO SSO ticket signature is invalid"
        payload = json.loads(_b64decode(encoded).decode("utf-8"))
    except (ValueError, TypeError, UnicodeError, json.JSONDecodeError):
        return None, "WAO SSO ticket format is invalid"

    current = int(time.time() if now is None else now)
    issued_at = payload.get("iat")
    expires_at = payload.get("exp")
    member_id = payload.get("sub")
    nonce = payload.get("nonce")
    role = payload.get("systemRole")
    if payload.get("v") != 1 or payload.get("aud") != _AUDIENCE:
        return None, "WAO SSO ticket audience is invalid"
    if not isinstance(issued_at, int) or not isinstance(expires_at, int):
        return None, "WAO SSO ticket time is invalid"
    if issued_at > current + _MAX_CLOCK_SKEW_SECONDS or expires_at <= current:
        return None, "WAO SSO ticket has expired"
    if expires_at - issued_at > 120:
        return None, "WAO SSO ticket lifetime is invalid"
    if not isinstance(member_id, str) or not _MEMBER_ID.fullmatch(member_id):
        return None, "WAO member id is invalid"
    if not isinstance(nonce, str) or len(nonce) < 32 or len(nonce) > 64:
        return None, "WAO SSO ticket nonce is invalid"
    if role not in _ROLES:
        return None, "WAO SSO role is invalid"
    return payload, ""


def authenticate(token):
    payload, error = _decode_ticket(token)
    if error:
        return None, error

    from django.core.cache import cache

    nonce_key = "wao:jumpserver:sso:" + payload["nonce"]
    if not cache.add(nonce_key, "used", timeout=130):
        return None, "WAO SSO ticket has already been used"

    name = str(payload.get("name") or payload["sub"]).strip()[:128]
    email = str(payload.get("email") or (payload["sub"] + "@wao.local")).strip()[:254]
    return {
        "username": "wao_" + payload["sub"],
        "name": name,
        "email": email,
        "system_role_name": payload["systemRole"],
        "next_url": "/",
    }, ""
