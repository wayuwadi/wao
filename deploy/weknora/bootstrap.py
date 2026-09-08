#!/usr/bin/env python3
"""Initialize private runtime configuration without printing credentials."""
import os
from pathlib import Path
import secrets
import sys

root = Path(sys.argv[1])
models_path = root / "upstream/config/builtin_models.yaml"
if not models_path.exists():
    models_path.write_text("builtin_models: []\n")
env_path = root / "upstream/.env"
if not env_path.exists():
    values = {
        "WEKNORA_VERSION": "v0.8.0",
        "FRONTEND_PORT": "127.0.0.1:18781", "APP_PORT": "127.0.0.1:18780",
        "GIN_MODE": "release", "LOG_LEVEL": "info", "DEFAULT_LOCALE": "zh-CN",
        "DB_DRIVER": "postgres", "DB_HOST": "postgres", "DB_PORT": "5432",
        "DB_USER": "weknora", "DB_NAME": "weknora", "DB_PASSWORD": secrets.token_hex(24),
        "REDIS_ADDR": "redis:6379", "REDIS_PASSWORD": secrets.token_hex(24),
        "JWT_SECRET": secrets.token_hex(32), "SYSTEM_AES_KEY": secrets.token_hex(16),
        "STORAGE_TYPE": "local", "LOCAL_STORAGE_BASE_DIR": "/data/files",
        "RETRIEVE_DRIVER": "postgres", "STREAM_MANAGER_TYPE": "redis",
        "OLLAMA_OPTIONAL": "true", "OLLAMA_BASE_URL": "http://127.0.0.1:11434",
        "FRONTEND_BASE_URL": "https://wao.wayuwa.cn/weknora",
        "WEKNORA_SANDBOX_DOCKER_ENABLED": "false", "NEO4J_ENABLE": "false",
        "DOCREADER_GRPC_MAX_WORKERS": "2", "DOCREADER_PDF_RENDER_PARALLELISM": "1",
        "DOCREADER_MARKITDOWN_MAX_WORKERS": "1", "MAX_FILE_SIZE_MB": "50",
    }
    fd = os.open(env_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as stream:
        stream.write("\n".join(f"{key}={value}" for key, value in values.items()) + "\n")
    print("Created private WeKnora configuration")
else:
    print("Preserved existing WeKnora configuration")
