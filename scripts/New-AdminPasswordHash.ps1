param(
  [string]$Jar = (Join-Path $PSScriptRoot '../target/springboot-wxcloudrun-1.0.jar'),
  [string]$Java = 'java'
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Jar)) { throw '请先运行 Maven package，或用 -Jar 指定已构建的后端 JAR。' }
$taskTemp = Join-Path ([IO.Path]::GetTempPath()) ('yiyao-hash-' + [guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($taskTemp) | Out-Null
$archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Jar).Path)
try {
  $libraries = @($archive.Entries | Where-Object { $_.FullName -match '^BOOT-INF/lib/(spring-security-crypto|spring-jcl)-[^/]+\.jar$' })
  if ($libraries.Count -ne 2) { throw 'JAR 中缺少密码编码依赖' }
  foreach ($entry in $libraries) { [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,(Join-Path $taskTemp $entry.Name)) }
} finally { $archive.Dispose() }
try {
  $secret = Read-Host '输入管理员密码（至少12字符，最多72个UTF-8字节）' -AsSecureString
  $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
  try { $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
  finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
  $info = [Diagnostics.ProcessStartInfo]::new()
  $info.FileName = $Java; $info.UseShellExecute = $false; $info.CreateNoWindow = $true
  $info.RedirectStandardInput = $true; $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
  $info.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
  $info.ArgumentList.Add('--class-path')
  $info.ArgumentList.Add(($libraries | ForEach-Object { Join-Path $taskTemp $_.Name }) -join [IO.Path]::PathSeparator)
  $info.ArgumentList.Add((Join-Path $PSScriptRoot 'AdminPasswordHash.java'))
  $process = [Diagnostics.Process]::Start($info)
  $process.StandardInput.WriteLine($plain); $process.StandardInput.Close(); $plain = $null
  $hash = $process.StandardOutput.ReadToEnd().Trim(); $null = $process.StandardError.ReadToEnd()
  $process.WaitForExit()
  if ($process.ExitCode -ne 0 -or $hash -notmatch '^\$2[aby]\$12\$') { throw '生成失败：需 JDK21+，密码至少12字符且最多72个UTF-8字节。' }
  Write-Output $hash
} finally {
  # Remove only the two library files created for this invocation, then the empty directory.
  foreach ($entry in $libraries) { $file=Join-Path $taskTemp $entry.Name; if(Test-Path -LiteralPath $file){Remove-Item -LiteralPath $file -Force} }
  if(Test-Path -LiteralPath $taskTemp){Remove-Item -LiteralPath $taskTemp}
}
