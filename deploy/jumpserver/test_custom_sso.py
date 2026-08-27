import base64
import hashlib
import hmac
import importlib.util
import json
import os
import sys
import types
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("custom_sso.py")
SPEC = importlib.util.spec_from_file_location("wao_jumpserver_custom_sso", MODULE_PATH)
custom_sso = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(custom_sso)
SECRET = "0123456789abcdef0123456789abcdef"


class FakeCache:
    def __init__(self):
        self.keys = set()

    def add(self, key, value, timeout):
        if key in self.keys:
            return False
        self.keys.add(key)
        return True


class CustomSsoTest(unittest.TestCase):
    def setUp(self):
        os.environ["JUMPSERVER_SSO_SHARED_SECRET"] = SECRET
        self.cache = FakeCache()
        django = types.ModuleType("django")
        django_core = types.ModuleType("django.core")
        django_cache = types.ModuleType("django.core.cache")
        django_cache.cache = self.cache
        sys.modules.update({
            "django": django,
            "django.core": django_core,
            "django.core.cache": django_cache,
        })

    def ticket(self, now=1_800_000_000, **overrides):
        payload = {
            "v": 1,
            "aud": "jumpserver",
            "sub": "m-1",
            "name": "测试成员",
            "email": "member@example.com",
            "systemRole": "SystemUser",
            "iat": now,
            "exp": now + 45,
            "nonce": "12345678-1234-1234-1234-123456789abc",
        }
        payload.update(overrides)
        encoded = base64.urlsafe_b64encode(
            json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        ).rstrip(b"=").decode("ascii")
        signature = base64.urlsafe_b64encode(
            hmac.new(SECRET.encode(), encoded.encode("ascii"), hashlib.sha256).digest()
        ).rstrip(b"=").decode("ascii")
        return encoded + "." + signature

    def test_accepts_valid_ticket_and_rejects_replay(self):
        token = self.ticket()
        original_time = custom_sso.time.time
        custom_sso.time.time = lambda: 1_800_000_000
        try:
            user, error = custom_sso.authenticate(token)
            repeated, repeated_error = custom_sso.authenticate(token)
        finally:
            custom_sso.time.time = original_time

        self.assertEqual(error, "")
        self.assertEqual(user["username"], "wao_m-1")
        self.assertEqual(user["system_role_name"], "SystemUser")
        self.assertIsNone(repeated)
        self.assertIn("already been used", repeated_error)

    def test_rejects_tampering_expiry_and_unknown_role(self):
        token = self.ticket()
        payload, signature = token.split(".")
        changed = ("A" if payload[0] != "A" else "B") + payload[1:] + "." + signature
        self.assertIn("signature", custom_sso._decode_ticket(changed, 1_800_000_000)[1])
        self.assertIn("expired", custom_sso._decode_ticket(
            self.ticket(exp=1_799_999_999), 1_800_000_000
        )[1])
        self.assertIn("role", custom_sso._decode_ticket(
            self.ticket(systemRole="Owner"), 1_800_000_000
        )[1])

    def test_rejects_weak_runtime_secret(self):
        os.environ["JUMPSERVER_SSO_SHARED_SECRET"] = "short"
        self.assertIn("shared secret", custom_sso._decode_ticket(self.ticket(), 1_800_000_000)[1])

    def test_adds_only_named_wao_members_to_asset_group(self):
        group = types.SimpleNamespace(name="WAO 基础设施维护者")

        class GroupQuery:
            @staticmethod
            def first():
                return group

        class GroupManager:
            @staticmethod
            def filter(**kwargs):
                self.assertEqual(kwargs, {"name": "WAO 基础设施维护者"})
                return GroupQuery()

        class OrgContext:
            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc, traceback):
                return False

        orgs = types.ModuleType("orgs")
        orgs_models = types.ModuleType("orgs.models")
        orgs_models.Organization = types.SimpleNamespace(default=lambda: "default-org")
        orgs_utils = types.ModuleType("orgs.utils")
        orgs_utils.tmp_to_org = lambda organization: OrgContext()
        users = types.ModuleType("users")
        users_models = types.ModuleType("users.models")
        users_models.UserGroup = types.SimpleNamespace(objects=GroupManager())
        sys.modules.update({
            "orgs": orgs,
            "orgs.models": orgs_models,
            "orgs.utils": orgs_utils,
            "users": users,
            "users.models": users_models,
        })

        added = []
        authorized = types.SimpleNamespace(
            username="wao_member-1",
            name="娃哥",
            groups=types.SimpleNamespace(add=added.append),
        )
        unrelated = types.SimpleNamespace(
            username="wao_member-2",
            name="其他成员",
            groups=types.SimpleNamespace(add=added.append),
        )

        custom_sso.grant_wao_asset_group(None, authorized)
        custom_sso.grant_wao_asset_group(None, unrelated)

        self.assertEqual(added, [group])


if __name__ == "__main__":
    unittest.main()
