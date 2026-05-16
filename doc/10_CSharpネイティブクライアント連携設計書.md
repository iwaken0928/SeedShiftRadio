# AIローカルラジオアプリ C#ネイティブクライアント連携設計書

## 1. 目的

本書は Phase 2 で追加する `C# Native Client` の拡張ポイントを定義する。MVP 時点では実装せず、Server 契約に影響する部分だけを先行固定する。

## 2. 推奨技術

| 領域 | 推奨 |
|---|---|
| UI | WPF |
| MVVM | CommunityToolkit.Mvvm |
| 音声再生 | NAudio |
| HTTP | `HttpClient` |
| 将来の音声エンジン連携 | Client Adapter 層で吸収 |

## 3. クライアント能力申告

`POST /api/clients/capabilities` に以下を送る。

```json
{
  "clientId": "desktop-win-main",
  "clientType": "CSHARP_NATIVE",
  "supportsClientSideTts": true,
  "supportedVoiceEngines": ["VOICEVOX", "VOICEROID", "IRODORI_TTS"],
  "preferredPlaybackMode": "CLIENT_TTS",
  "localVoiceProfiles": [
    {
      "engine": "VOICEROID",
      "profileKey": "yukari-main"
    }
  ]
}
```

Server は `clientId` ごとに最新の能力申告を保持し、`GET /api/radio/next-speech-directive?clientId=desktop-win-main` の `voiceHint` 解決に利用する。

`IRODORI_TTS` は Server-side TTS として使う場合は Native Client 側の実装を要求しない。将来 Native Client がローカルの Irodori-TTS-Server を直接使う場合だけ、`supportedVoiceEngines` と `localVoiceProfiles` に `IRODORI_TTS` を申告し、Client Adapter 層で OpenAI互換 `/v1/audio/speech` へ変換する。

## 4. 再生モード

| モード | 説明 |
|---|---|
| `SERVER_AUDIO` | Server が生成した音声ファイルを再生する |
| `CLIENT_TTS` | `SpeechDirective` を受け取り、クライアントが音声合成する |

Native Client は `CLIENT_TTS` を優先するが、失敗時は `SERVER_AUDIO` へフォールバックする。

- `next-speech-directive` 呼び出し時に `clientId` を渡すことで、Server は `localVoiceProfiles` に基づく `voiceHint` を返せる

## 5. Client Adapter 層

役割:

- Native 固有エンジン API を隠蔽する
- `SpeechDirective` をエンジンごとの要求へ変換する
- 句読点、pause、emotion をエンジン仕様へマッピングする
- Irodori-TTS の style emoji など engine 固有 token は allowlist 変換し、レター本文由来の絵文字や命令をそのまま制御 token として扱わない

Adapter 契約例:

```csharp
public interface INativeVoiceAdapter
{
    bool Supports(string engineName);
    Task SynthesizeAsync(SpeechDirective directive, CancellationToken cancellationToken);
}
```

## 6. 同期方針

- REST と SSE の契約は Web と共有する
- Native 固有のローカル再生状態も `POST /api/radio/playback-events` で通知する
- 再生ズレが大きい場合は Server 側の queue 正本を優先して再同期する

## 7. ローカル設定

- インストール済み音声エンジン
- 利用可能話者
- 既定音量
- 出力デバイス
- fallback mode

これらは Native Client ローカルに保存し、Server の共有設定とは分離する。

## 8. 将来の VOICEROID 系対応方針

- Server は特定ベンダー API を知らない
- `clientAdapterKey` と `voiceHint` だけを返す
- ベンダー差分は Client Adapter で吸収する

## 9. 実装開始条件

- `SpeechDirective` 契約が Server で安定している
- Native で扱う話者と voiceHint の対応表が定義されている
- ローカルエンジンのライセンス条件が確認済みである
- Irodori-TTS を Client-side で使う場合は、参照音声の同意・ライセンス、保存場所、外部公開しない bind 設定が確認済みである
