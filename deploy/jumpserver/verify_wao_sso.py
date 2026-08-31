"""Verify that a real persisted WAO identity can establish a JumpServer session."""

import base64
import hashlib
import hmac
import json
import os
import re
import time
import uuid

import requests
from data.auth.custom_sso import authenticate
from django.contrib.auth import get_user_model


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


secret = os.environ.get("JUMPSERVER_SSO_SHARED_SECRET", "")
require(len(secret) >= 32, "JumpServer SSO shared secret is unavailable")

User = get_user_model()
user = User.objects.filter(username__startswith="wao_", is_active=True).order_by("date_joined").first()
require(user is not None, "No persisted WAO JumpServer identity is available for SSO verification")

member_id = user.username.removeprefix("wao_")
require(re.fullmatch(r"[A-Za-z0-9_-]{1,96}", member_id), "Persisted WAO identity is invalid")
now = int(time.time())
payload = {
    "v": 1,
    "aud": "jumpserver",
    "sub": member_id,
    "name": user.name,
    "email": user.email or f"{member_id}@wao.local",
    "systemRole": "SystemUser",
    "iat": now,
    "exp": now + 45,
    "nonce": str(uuid.uuid4()),
}
encoded = base64.urlsafe_b64encode(
    json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
).rstrip(b"=").decode("ascii")
signature = base64.urlsafe_b64encode(
    hmac.new(secret.encode("utf-8"), encoded.encode("ascii"), hashlib.sha256).digest()
).rstrip(b"=").decode("ascii")

# 先单独验证适配器对同一张真实票据的解析结果。随后使用另一张 nonce 走
# 公网 HTTPS 入口，避免 manage.py shell 缺少 JumpServer 请求线程上下文时，
# 登录信号把发布门禁误判为失败。
adapter_token = f"{encoded}.{signature}"
user_count_before = User.objects.count()
userinfo, error = authenticate(adapter_token)
require(not error, "JumpServer Custom SSO rejected a valid WAO ticket")
require(userinfo is not None, "JumpServer Custom SSO did not return an identity")
require(userinfo.get("username") == user.username, "JumpServer Custom SSO returned another identity")

# 适配器验证会消费 nonce，真实入口必须使用一张新票据。
payload["nonce"] = str(uuid.uuid4())
encoded = base64.urlsafe_b64encode(
    json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
).rstrip(b"=").decode("ascii")
signature = base64.urlsafe_b64encode(
    hmac.new(secret.encode("utf-8"), encoded.encode("ascii"), hashlib.sha256).digest()
).rstrip(b"=").decode("ascii")

session = requests.Session()
response = session.get(
    "https://jms.wayuwa.cn/api/v1/authentication/custom-sso/login/",
    params={"token": f"{encoded}.{signature}"},
    allow_redirects=False,
    timeout=20,
)
require(response.status_code == 302, f"JumpServer Custom SSO login returned HTTP {response.status_code}")

profile = session.get(
    "https://jms.wayuwa.cn/api/v1/users/profile/",
    timeout=20,
)
require(profile.status_code == 200, f"JumpServer authenticated profile returned HTTP {profile.status_code}")
profile_data = profile.json()
require(profile_data.get("username") == user.username, "JumpServer session belongs to another identity")
require(User.objects.count() == user_count_before, "SSO verification unexpectedly created a user")

print("JumpServer existing WAO identity HTTPS SSO session verification passed")
