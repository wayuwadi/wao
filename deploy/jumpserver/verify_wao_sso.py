"""Verify that a real persisted WAO identity can establish a JumpServer session."""

import base64
import hashlib
import hmac
import json
import os
import re
import time
import uuid

from django.contrib.auth import get_user_model
from django.test import Client


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

user_count_before = User.objects.count()
client = Client()
response = client.get(
    "/api/v1/authentication/custom-sso/login/",
    {"token": f"{encoded}.{signature}"},
    secure=True,
    HTTP_HOST="jms.wayuwa.cn",
)
require(response.status_code == 302, "JumpServer Custom SSO did not redirect after authentication")
require(
    str(client.session.get("_auth_user_id")) == str(user.pk),
    "JumpServer Custom SSO did not establish the expected user session",
)
require(User.objects.count() == user_count_before, "SSO verification unexpectedly created a user")
client.logout()

print("JumpServer existing WAO identity SSO session verification passed")
