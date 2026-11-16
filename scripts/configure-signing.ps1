[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$')]
    [string]$Repository = 'Tagshy/E-reader',
    [string]$SigningDirectory = (Join-Path $env:LOCALAPPDATA 'Leitura\Signing')
)
$ErrorActionPreference = 'Stop'
$ghCommand = Get-Command gh -ErrorAction SilentlyContinue
if (-not $ghCommand) { throw 'Instale o GitHub CLI oficial (https://cli.github.com) e execute gh auth login antes de continuar.' }
& $ghCommand.Source auth status
if ($LASTEXITCODE -ne 0) { throw 'Execute gh auth login e conecte sua conta do GitHub.' }
$keytoolCommand = Get-Command keytool -ErrorAction SilentlyContinue
$keytoolPath = if ($keytoolCommand) { $keytoolCommand.Source } else { 'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe' }
if (-not (Test-Path -LiteralPath $keytoolPath)) { throw 'Instale um JDK ou o Android Studio para gerar a assinatura.' }
$targetDirectory = [IO.Path]::GetFullPath($SigningDirectory)
$projectDirectory = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
if ($targetDirectory.Equals($projectDirectory, [StringComparison]::OrdinalIgnoreCase) -or
    $targetDirectory.StartsWith($projectDirectory + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'A chave privada deve ficar fora do repositório.'
}
New-Item -ItemType Directory -Path $targetDirectory -Force | Out-Null
$keyPath = Join-Path $targetDirectory 'leitura-release.jks'
$passwordPath = Join-Path $targetDirectory 'password.dpapi'
$certificatePath = Join-Path $targetDirectory 'public-certificate.der'
$hasKey = Test-Path -LiteralPath $keyPath
$hasPassword = Test-Path -LiteralPath $passwordPath
if ($hasKey -ne $hasPassword) { throw 'Backup de assinatura incompleto. Recupere a chave e a senha originais; não gere outra chave.' }
if ($hasPassword) {
    $protectedPassword = Get-Content -LiteralPath $passwordPath -Raw | ConvertTo-SecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($protectedPassword)
    try { $signingPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
} else {
    $randomBytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($randomBytes) } finally { $rng.Dispose() }
    $signingPassword = [Convert]::ToBase64String($randomBytes)
}
$oldSigningPassword = [Environment]::GetEnvironmentVariable('LEITURA_SIGNING_PASSWORD', 'Process')
try {
    [Environment]::SetEnvironmentVariable('LEITURA_SIGNING_PASSWORD', $signingPassword, 'Process')
    if (-not $hasKey) {
        & $keytoolPath -genkeypair -keystore $keyPath -storetype JKS -alias leitura -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Leitura, O=Leitura, C=BR' -storepass:env LEITURA_SIGNING_PASSWORD -keypass:env LEITURA_SIGNING_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'Não foi possível criar a chave privada.' }
        ConvertTo-SecureString $signingPassword -AsPlainText -Force | ConvertFrom-SecureString | Set-Content -LiteralPath $passwordPath -Encoding UTF8
    }
    & $keytoolPath -exportcert -keystore $keyPath -alias leitura -storepass:env LEITURA_SIGNING_PASSWORD -file $certificatePath
    if ($LASTEXITCODE -ne 0) { throw 'Não foi possível conferir a chave existente.' }
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $fingerprint = -join ($sha.ComputeHash([IO.File]::ReadAllBytes($certificatePath)) | ForEach-Object { $_.ToString('x2') }) }
    finally { $sha.Dispose() }
    function Set-PrivateGitHubSecret([string]$Name, [string]$Value) {
        $start = New-Object Diagnostics.ProcessStartInfo
        $start.FileName = $ghCommand.Source
        $start.Arguments = 'secret set ' + $Name + ' --repo ' + $Repository
        $start.UseShellExecute = $false
        $start.CreateNoWindow = $true
        $start.RedirectStandardInput = $true
        $process = New-Object Diagnostics.Process
        $process.StartInfo = $start
        [void]$process.Start()
        $process.StandardInput.Write($Value)
        $process.StandardInput.Close()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw ('Não foi possível cadastrar o Secret ' + $Name) }
        $process.Dispose()
    }
    Set-PrivateGitHubSecret 'ANDROID_KEYSTORE_BASE64' ([Convert]::ToBase64String([IO.File]::ReadAllBytes($keyPath)))
    Set-PrivateGitHubSecret 'ANDROID_KEYSTORE_PASSWORD' $signingPassword
    Set-PrivateGitHubSecret 'ANDROID_SIGNING_CERT_SHA256' $fingerprint
    Write-Host ('Assinatura fixa cadastrada nos GitHub Secrets de ' + $Repository + '.')
    Write-Host ('Chave privada local: ' + $keyPath)
    Write-Host 'A senha local usa DPAPI e só pode ser aberta pela mesma conta do Windows. Preserve a chave e guarde a senha também em um gerenciador de senhas para recuperação em outro computador.'
} finally {
    [Environment]::SetEnvironmentVariable('LEITURA_SIGNING_PASSWORD', $oldSigningPassword, 'Process')
    $signingPassword = $null
}
