# MusicGen Worker

`doc/09_MusicGen連携設計書.md` に合わせた FastAPI worker です。

現段階では実モデル推論の代わりに deterministic な WAV を生成し、Server 側の `submit -> poll -> generated_asset` 契約を先に成立させます。

## 起動

```bash
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --host 127.0.0.1 --port 8000
```

共有データルートは `SEEDSHIFT_MUSICGEN_DATA_ROOT` で上書きできます。既定値は `./data` です。
GitLab CI の `deploy-musicgen` では container 内の `SEEDSHIFT_MUSICGEN_DATA_ROOT=/data` を使い、`MUSICGEN_DATA_VOLUME` を設定した場合だけ Podman volume または host path を `/data` へ mount します。
