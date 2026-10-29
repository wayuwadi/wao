param([string]$SourceRoot=$env:WORKSPACE,[Parameter(Mandatory)][string]$RuntimeRoot,[switch]$StageOnly)
$ErrorActionPreference='Stop'
$env:PSModulePath="$PSHOME/Modules;$env:ProgramFiles/WindowsPowerShell/Modules"
$runtimeRoot=[IO.Path]::GetFullPath($RuntimeRoot)
$base=Join-Path $runtimeRoot 'deploy/data/windows-server'
Import-Module (Join-Path $PSScriptRoot 'WindowsBuildInputs.psm1') -Force
$git="$runtimeRoot/work/tools/mingit/cmd/git.exe"
$lock=$null
function Run([string]$exe,[string[]]$arguments){& $exe @arguments;if($LASTEXITCODE -ne 0){throw "Build command failed ($LASTEXITCODE): $exe"}}
function Atomic($path,$value){$temp="$path.$([Guid]::NewGuid().ToString('N')).tmp";$value|ConvertTo-Json -Depth 20|Set-Content $temp -Encoding UTF8;Move-Item $temp $path -Force}
try {
  $lock=[IO.File]::Open("$base/deploy.lock",'OpenOrCreate','ReadWrite','None')
  if(!$SourceRoot -or !(Test-Path "$SourceRoot/.git")){throw 'A full, independent Git checkout is required'}
  $SourceRoot=[IO.Path]::GetFullPath($SourceRoot)
  if($SourceRoot -eq $runtimeRoot){throw 'The snapshot and private runtime directory cannot be used as the SCM workspace'}
  Set-Location $SourceRoot
  $sha=(& $git rev-parse HEAD).Trim();if($LASTEXITCODE -ne 0 -or $sha -notmatch '^[a-f0-9]{40}$'){throw 'Invalid Git commit'}
  if($env:GIT_COMMIT -and $env:GIT_COMMIT -ne $sha){throw 'Jenkins checkout SHA mismatch'}
  Run $git @('diff','--exit-code','HEAD','--')
  $env:JAVA_HOME="$runtimeRoot/work/tools/jdk-17.0.20.1+1"
  $nodeRoot="$runtimeRoot/work/tools/node-v24.21.0-win-x64"
  $env:Path="$env:JAVA_HOME/bin;$nodeRoot;$runtimeRoot/work/tools/mingit/cmd;$env:Path"
  $env:UV_THREADPOOL_SIZE='1';$env:WAO_GIT_SHA=$sha;$env:WAO_VUE_DEVTOOLS='false'
  Remove-Item Env:WAO_GIT_HISTORY -ErrorAction SilentlyContinue
  $previous=Get-Content "$base/active.json" -Raw|ConvertFrom-Json
  $commitMap=Join-Path $base 'open-source-commit-map.private'
  $changeBase=Resolve-WaoReleaseBase $git $SourceRoot $previous.sha $commitMap
  $env:WAO_CHANGE_BASE=$changeBase
  $legacyArgument=Get-WaoLegacyMigrationArguments (Join-Path $base 'production-build.private.json') $SourceRoot
  $backendChanged=$true
  $backendChanges=@(& $git diff --name-only $changeBase $sha -- wao-server wao-gateway pom.xml)
  if($LASTEXITCODE -ne 0){throw 'Backend change planning failed'}
  $backendChanged=$backendChanges.Count -gt 0
  $backendSha=if($backendChanged){$sha}elseif($previous.backendSha){$previous.backendSha}else{$previous.sha}
  Write-Host "Testing and building Git commit $sha"
  Run "$runtimeRoot/work/tools/apache-maven-3.9.9/bin/mvn.cmd" @('-s',"$runtimeRoot/work/maven-settings.xml",'-B','-pl','wao-server,wao-gateway','-am',$legacyArgument,'clean','verify')
  foreach($component in @('wao-game','wao-web')){
    Set-Location "$SourceRoot/$component"
    Run "$nodeRoot/npm.cmd" @('ci','--registry=https://registry.npmmirror.com',"--cache=$runtimeRoot/work/npm-cache",'--no-audit','--no-fund')
    if($component -eq 'wao-game'){
      foreach($game in @('dino','rift','starfield','beasts','camp')){Run "$nodeRoot/npm.cmd" @('run',"build:$game")}
    }else{
      Run "$nodeRoot/npm.cmd" @('run','type-check')
      Run "$nodeRoot/npm.cmd" @('run','test:stability')
      Run "$nodeRoot/node.exe" @('node_modules/vite/bin/vite.js','build')
    }
  }
  Set-Location $SourceRoot
  if((& $git rev-parse HEAD).Trim() -ne $sha){throw 'Source commit changed during build'}
  if($backendChanged){
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive=[IO.Compression.ZipFile]::OpenRead("$SourceRoot/wao-server/target/wao-server-0.1.0-SNAPSHOT.jar")
    try {
      $entry=$archive.GetEntry('BOOT-INF/classes/db/migration/V33__task_completed_at_and_webhook_switch.sql')
      if(!$entry){throw 'Packaged legacy migration is missing'}
      $stream=$entry.Open();$digest=[Security.Cryptography.SHA256]::Create()
      try{$packagedHash=([BitConverter]::ToString($digest.ComputeHash($stream))).Replace('-','')}finally{$digest.Dispose();$stream.Dispose()}
      $protected=Get-Content (Join-Path $base 'production-build.private.json') -Raw|ConvertFrom-Json
      if($packagedHash -ne $protected.legacyMigrationSha256){throw 'Packaged migration checksum changed; deployment is blocked'}
    } finally {$archive.Dispose()}
  }
  $id=$sha+'-'+[DateTime]::UtcNow.ToString('yyyyMMddHHmmssfff')
  $release="$base/releases/$id"
  if(Test-Path $release){throw 'Immutable release already exists'}
  New-Item -ItemType Directory -Path "$release/web","$release/games"|Out-Null
  if($backendChanged){
    Copy-Item "$SourceRoot/wao-server/target/wao-server-0.1.0-SNAPSHOT.jar" "$release/server.jar"
    Copy-Item "$SourceRoot/wao-gateway/target/wao-gateway-0.1.0-SNAPSHOT.jar" "$release/gateway.jar"
  }else{
    foreach($name in @('server','gateway')){
      $existing="$base/releases/$($previous.id)/$name.jar"
      if((Get-FileHash $existing).Hash -ne $previous.($name+'Hash')){throw 'Previous backend artifact checksum mismatch'}
      Copy-Item $existing "$release/$name.jar"
    }
    Write-Host 'Static-only changes: reuse verified backend artifacts and keep backend processes running'
  }
  Copy-Item "$SourceRoot/wao-web/dist/*" "$release/web" -Recurse
  Copy-Item "$SourceRoot/wao-game/dist/*" "$release/games" -Recurse
  foreach($file in @('server.jar','gateway.jar','web/index.html','web/edge-bootstrap.js','games/dino/index.html','games/rift/index.html','games/starfield/index.html','games/beasts/index.html','games/camp/index.html')){if(!(Test-Path "$release/$file")){throw "Missing artifact: $file"}}
  $hashes=@{}
  Get-ChildItem $release -File -Recurse|ForEach-Object {$relative=$_.FullName.Substring([IO.Path]::GetFullPath($release).Length+1).Replace('\','/');$hashes[$relative]=(Get-FileHash $_.FullName -Algorithm SHA256).Hash}
  $manifest=@{id=$id;sha=$sha;created=[DateTime]::UtcNow.ToString('o');serverHash=$hashes['server.jar'];gatewayHash=$hashes['gateway.jar'];files=$hashes;javaTests='passed';source='GitHub main';buildNumber=$env:BUILD_NUMBER;backendSha=$backendSha;backendChanged=$backendChanged}
  Atomic "$release/manifest.json" $manifest
  if($StageOnly){Atomic "$base/staged.json" $manifest;Write-Host "Staged $id";exit 0}
  Atomic "$base/queue/request.json" $manifest
  Write-Host "Waiting for verified deployment: $id"
  $deadline=(Get-Date).AddMinutes(6)
  do {
    Start-Sleep -Seconds 2
    if(Test-Path "$base/queue/result.json"){
      $result=Get-Content "$base/queue/result.json" -Raw|ConvertFrom-Json
      if($result.id -eq $id){if($result.status -ne 'SUCCESS'){throw ('Deployment failed: '+$result.message)};Write-Host "SUCCESS: deployed Git SHA $sha";exit 0}
    }
  }while((Get-Date) -lt $deadline)
  throw 'Deployment confirmation timed out'
}finally{if($lock){$lock.Dispose()}}
