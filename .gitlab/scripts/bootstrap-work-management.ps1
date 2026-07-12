[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

function ConvertTo-WslPath([string]$Path) {
    $resolved = [System.IO.Path]::GetFullPath($Path)
    if ($resolved -notmatch '^([A-Za-z]):\\(.*)$') {
        throw "WSL pathへ変換できません: $resolved"
    }
    return "/mnt/$($Matches[1].ToLowerInvariant())/$($Matches[2].Replace('\', '/'))"
}

$codexHome = if ($env:CODEX_HOME) { $env:CODEX_HOME } else { Join-Path $HOME '.codex' }
$apiScriptWindows = Join-Path $codexHome 'skills\gitlab-work-management\scripts\gitlab-project-api.sh'
if (-not (Test-Path -LiteralPath $apiScriptWindows)) {
    throw "GitLab API helperが見つかりません: $apiScriptWindows"
}
$apiScript = ConvertTo-WslPath $apiScriptWindows

$tempDir = Join-Path $PSScriptRoot '..\.bootstrap-tmp'
$payloadPath = Join-Path $tempDir 'payload.json'
$payloadWslPath = '.gitlab/.bootstrap-tmp/payload.json'
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null

function Invoke-GitLabApi([string]$Method, [string]$Resource, [hashtable]$Body = $null) {
    if ($null -ne $Body) {
        $Body | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $payloadPath -Encoding utf8NoBOM
        $result = & bash $apiScript $Method $Resource $payloadWslPath
    } else {
        $result = & bash $apiScript $Method $Resource
    }
    if ($LASTEXITCODE -ne 0) {
        throw "GitLab API failed: $Method $Resource"
    }
    if ($result) { return ($result | ConvertFrom-Json) }
}

$labels = @(
    @{ name = 'kind::feature'; color = '#1F75CB'; description = '新機能・機能完成' },
    @{ name = 'kind::bug'; color = '#D73A4A'; description = '不具合修正' },
    @{ name = 'kind::docs'; color = '#0075CA'; description = '設計書・運用文書' },
    @{ name = 'kind::maintenance'; color = '#6E7681'; description = '保守・依存更新・整理' },
    @{ name = 'area::server'; color = '#0052CC'; description = 'Spring Boot server' },
    @{ name = 'area::web'; color = '#0E8A16'; description = 'Next.js web client' },
    @{ name = 'area::worker'; color = '#5319E7'; description = 'MusicGen worker / AI worker' },
    @{ name = 'area::ops'; color = '#FBCA04'; description = 'CI/CD、container、compose、運用' },
    @{ name = 'area::architecture'; color = '#B60205'; description = 'API契約・横断設計' },
    @{ name = 'priority::P0'; color = '#B60205'; description = 'MVP成立を妨げる最優先課題' },
    @{ name = 'priority::P1'; color = '#D93F0B'; description = '品質・運用完成度に必要な課題' },
    @{ name = 'priority::P2'; color = '#FBCA04'; description = '改善・追随課題' },
    @{ name = 'status::未着手'; color = '#C5DEF5'; description = '未整理または依存未確認' },
    @{ name = 'status::着手可能'; color = '#0E8A16'; description = '完了条件と依存が明確' },
    @{ name = 'status::作業中'; color = '#1D76DB'; description = '実装・検証中' },
    @{ name = 'status::確認待ち'; color = '#FBCA04'; description = '実装完了、人間の確認待ち' },
    @{ name = 'status::保留'; color = '#6E7681'; description = '外部要因または判断待ち' },
    @{ name = 'agent::codex'; color = '#7057FF'; description = 'Codexが主に実装する作業' },
    @{ name = 'agent::human'; color = '#F9D0C4'; description = '人間の判断・実環境操作が主となる作業' }
)

$issues = @(
    @{ id = 'P0-01'; title = 'LLM adapter を追加する'; area = 'area::server'; purpose = 'TemplateScriptProviderではなく実providerからGeneratedScriptを生成できるようにする。'; scope = 'ScriptProvider、ProviderRegistry、ProviderJobService、SettingsDocument.providers.llm'; docs = 'doc/06、doc/07、doc/15' },
    @{ id = 'P0-02b'; title = 'Irodori OpenAI TTS adapter のruntime反映を完成する'; area = 'area::server'; purpose = 'VoiceProfileの速度、provider option、参照音声、同意情報をIrodori TTS呼び出しへ安全に反映する。'; scope = 'TtsProvider、ProviderRegistry、ProviderHealthService、AssetService、station scope validation'; docs = 'doc/04、doc/07、doc/08、doc/15' },
    @{ id = 'P0-02c'; title = 'VoiceProfile と参照音声 metadata を拡張する'; area = 'area::architecture'; purpose = '局別voice割当、参照範囲、同意確認、path redactionを一貫して扱えるようにする。'; scope = 'voice_profile migration、VoiceProfileEntity、seed data、station validation'; docs = 'doc/04、doc/07、doc/08、doc/15' },
    @{ id = 'P0-03'; title = '音声生成 orchestration を明示 service へ分離する'; area = 'area::server'; purpose = 'scriptからTTS assetまでのprovider job、metadata、cache keyを追跡可能にする。'; scope = 'AssetService、ScriptGenerationService、生成orchestration'; docs = 'doc/04、doc/06、doc/07、doc/08、doc/15' },
    @{ id = 'P0-04'; title = 'LETTER 回答案生成を実装する'; area = 'area::server'; purpose = '信頼できないletter bodyを安全に要約・引用し、放送用台本へ変換する。'; scope = 'LetterService、ScriptGenerationService、prompt safety'; docs = 'doc/06、doc/08、doc/12、doc/15' },
    @{ id = 'P0-05'; title = 'provider error と fallback 連鎖を統一する'; area = 'area::architecture'; purpose = 'LLM、TTS、MUSICの失敗分類と縮退理由を共通契約へ揃える。'; scope = 'provider adapter共通result/error、provider_job.errorCode、degraded reason'; docs = 'doc/05、doc/07、doc/09、doc/11、doc/15' },
    @{ id = 'P0-05a'; title = 'Irodori 読み・style safety test を追加する'; area = 'area::server'; purpose = 'かな補正、style allowlist、letter由来token遮断をgolden testで固定する。'; scope = 'JapaneseScriptNormalizer、PronunciationDictionaryService、PersonaStyleResolver、JapaneseQualityGuard'; docs = 'doc/06、doc/08、doc/12、doc/14、doc/15' },
    @{ id = 'P0-06'; title = 'MusicGen worker に実モデル backend を追加する'; area = 'area::worker'; purpose = 'deterministic backend以外でWAVを生成し、実際に聴けるMVPへ進める。'; scope = 'workers/musicgen、worker backend、生成asset'; docs = 'doc/07、doc/09、doc/11、doc/15' },
    @{ id = 'P0-09'; title = 'ACE-Step 運用設定を compose・settings・docs へ接続する'; area = 'area::ops'; purpose = 'ServerからACE-StepまたはMusicGen workerへ疎通できる運用経路を整える。'; scope = 'infra/compose、settings、worker接続、運用手順'; docs = 'doc/07、doc/09、doc/11、doc/16、doc/17' },
    @{ id = 'P2-06'; title = 'Spring Boot 4.0.4 の採用理由と追随方針を明記する'; area = 'area::architecture'; purpose = 'Spring Boot versionの採用根拠と更新判断を設計・運用資料へ残す。'; scope = '依存version方針、更新手順、互換性確認'; docs = 'doc/01、doc/11、doc/15' }
)

try {
    $existingLabels = @(Invoke-GitLabApi GET 'labels?per_page=100')
    foreach ($label in $labels) {
        if ($existingLabels.name -contains $label.name) {
            Write-Host "SKIP label $($label.name)"
            continue
        }
        $created = Invoke-GitLabApi POST 'labels' $label
        Write-Host "CREATE label $($created.name)"
        $existingLabels += $created
    }

    $boards = @(Invoke-GitLabApi GET 'boards')
    $board = $boards | Where-Object name -eq 'Development' | Select-Object -First 1
    if (-not $board) {
        $board = Invoke-GitLabApi POST 'boards' @{ name = 'Development' }
        Write-Host "CREATE board Development"
    }
    $board = @(Invoke-GitLabApi GET 'boards') | Where-Object id -eq $board.id | Select-Object -First 1
    foreach ($statusName in @('status::着手可能', 'status::作業中', 'status::確認待ち', 'status::保留')) {
        if ($board.lists.label.name -contains $statusName) {
            Write-Host "SKIP board list $statusName"
            continue
        }
        $labelId = ($existingLabels | Where-Object name -eq $statusName | Select-Object -First 1).id
        $null = Invoke-GitLabApi POST "boards/$($board.id)/lists" @{ label_id = $labelId }
        Write-Host "CREATE board list $statusName"
        $board = @(Invoke-GitLabApi GET 'boards') | Where-Object id -eq $board.id | Select-Object -First 1
    }

    # GitLab 19 の Project Issues API は state=all を空配列として返すため、
    # opened / closed の両方を取得したい場合は state を省略する。
    $existingIssues = @(Invoke-GitLabApi GET 'issues?per_page=100')
    foreach ($issue in $issues) {
        if ($existingIssues.title -match "^\[$([regex]::Escape($issue.id))\]") {
            Write-Host "SKIP issue $($issue.id)"
            continue
        }
        $priority = $issue.id.Split('-')[0]
        $kind = if ($issue.id -eq 'P2-06') { 'kind::docs' } else { 'kind::feature' }
        $description = @"
## 目的

$($issue.purpose)

## 対象範囲

$($issue.scope)

## 参照資料

$($issue.docs)

## 作業内容

- [ ] 関係する設計書と現行実装を確認する
- [ ] 対象範囲を実装または更新する
- [ ] focused testを追加・実行する
- [ ] `doc/15_設計差分棚卸しと段階実装計画.md` の状態を更新する

## 変更してはいけない範囲

- API Firstと将来のC# Native Client互換を理由なく壊さない
- Serverが保持する再生・キュー・設定の正本をWebへ移さない
- 秘密値、letter body、prompt本文を標準ログへ出さない

## 完了条件

- [ ] `doc/15` に記載された完了条件を満たす
- [ ] 実装と関係設計書が一致する
- [ ] 対象領域のtestが成功する
- [ ] 未実施testと残課題をIssueコメントへ記録する

## Git運用

- 現在チェックアウト中のbranchで作業する
- branch作成・checkoutは、ユーザーまたはIssueで明示された場合のみ行う
- commit messageに `refs #<issue_no>` を含める
- 人間の確認後にIssueをcloseする
"@
        $created = Invoke-GitLabApi POST 'issues' @{
            title = "[$($issue.id)] $($issue.title)"
            description = $description
            labels = "$kind,$($issue.area),priority::$priority,status::着手可能,agent::codex"
        }
        Write-Host "CREATE issue #$($created.iid) $($issue.id)"
        $existingIssues += $created
    }
} finally {
    Remove-Item -LiteralPath $payloadPath -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $tempDir -Force -ErrorAction SilentlyContinue
}
