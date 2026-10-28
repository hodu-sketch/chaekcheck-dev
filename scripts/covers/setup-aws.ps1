# Cycle 26 step 1 (ADR-0163): the private S3 bucket for book covers, CloudFront in front of it (OAC, default
# *.cloudfront.net domain) and the PC upload profile (IAM user, s3:PutObject and s3:ListBucket on that bucket only).
#
# Run once from the dev repo root with an admin session, e.g. after `aws login --profile chaekcheck-admin`:
#   powershell -File scripts/covers/setup-aws.ps1 -AdminProfile chaekcheck-admin
# Safe to run again: each piece is looked up first and only made when missing.
# The upload key goes straight into the PC's AWS credentials file (profile chaekcheck-covers); it is never printed.
# Names that are not secret (bucket, distribution id, domain) go to data/covers-aws.json (git-ignored).
param(
	[Parameter(Mandatory = $true)][string]$AdminProfile,
	[string]$Aws = "$PSScriptRoot\..\..\data\tools\awscli\Amazon\AWSCLIV2\aws.exe",
	[string]$Region = 'ap-southeast-2',
	[string]$UploadProfile = 'chaekcheck-covers',
	[string]$UploaderName = 'chaekcheck-covers-uploader'
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path $Aws)) { $Aws = 'aws' }

function Invoke-Aws {
	param([string[]]$Arguments, [switch]$AllowFail)
	# Windows PowerShell turns a native command's stderr into errors; read it as text instead.
	$ErrorActionPreference = 'Continue'
	$out = & $Aws @Arguments --profile $AdminProfile --output json 2>&1 | ForEach-Object { "$_" }
	if ($LASTEXITCODE -ne 0) {
		if ($AllowFail) { return $null }
		throw "aws $($Arguments -join ' ') failed: $out"
	}
	$text = ($out | Out-String).Trim()
	if ($text) { return $text | ConvertFrom-Json }
	return $null
}

$work = Join-Path $env:TEMP "chaekcheck-covers-setup"
New-Item -ItemType Directory -Force $work | Out-Null
function Write-Json([string]$name, $value) {
	$path = Join-Path $work $name
	[IO.File]::WriteAllText($path, ($value | ConvertTo-Json -Depth 20), (New-Object Text.UTF8Encoding $false))
	return "file://$path"
}

$account = (Invoke-Aws @('sts', 'get-caller-identity')).Account
$bucket = "chaekcheck-covers-$account"
Write-Host "account $account, bucket $bucket, region $Region"

# 1. The bucket: private (all public access blocked), owner-enforced objects (the default).
$head = Invoke-Aws @('s3api', 'head-bucket', '--bucket', $bucket) -AllowFail
if ($LASTEXITCODE -ne 0) {
	Invoke-Aws @('s3api', 'create-bucket', '--bucket', $bucket, '--region', $Region,
		'--create-bucket-configuration', "LocationConstraint=$Region") | Out-Null
	Write-Host "bucket made"
}
Invoke-Aws @('s3api', 'put-public-access-block', '--bucket', $bucket, '--public-access-block-configuration',
	'BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true') | Out-Null

# 2. The origin access control (CloudFront signs its S3 reads with SigV4).
$oacName = "$bucket-oac"
$oac = (Invoke-Aws @('cloudfront', 'list-origin-access-controls')).OriginAccessControlList.Items |
	Where-Object { $_.Name -eq $oacName } | Select-Object -First 1
if (-not $oac) {
	$config = Write-Json 'oac.json' @{
		Name = $oacName; Description = 'chaekcheck covers'; SigningProtocol = 'sigv4'; SigningBehavior = 'always'
		OriginAccessControlOriginType = 's3'
	}
	$oac = (Invoke-Aws @('cloudfront', 'create-origin-access-control', '--origin-access-control-config', $config)).OriginAccessControl
	Write-Host "OAC made"
}
$oacId = $oac.Id

# 3. The distribution: default domain, https only, GET/HEAD, the managed CachingOptimized policy.
$origin = "$bucket.s3.$Region.amazonaws.com"
$dist = (Invoke-Aws @('cloudfront', 'list-distributions')).DistributionList.Items |
	Where-Object { $_.Origins.Items | Where-Object { $_.DomainName -eq $origin } } | Select-Object -First 1
if (-not $dist) {
	$config = Write-Json 'distribution.json' @{
		CallerReference = "chaekcheck-covers-$(Get-Date -Format yyyyMMddHHmmss)"
		Comment = 'chaekcheck covers'
		Enabled = $true
		PriceClass = 'PriceClass_200'
		HttpVersion = 'http2and3'
		IsIPV6Enabled = $true
		Origins = @{ Quantity = 1; Items = @(@{
			Id = 's3-covers'; DomainName = $origin; OriginAccessControlId = $oacId
			S3OriginConfig = @{ OriginAccessIdentity = '' }
		}) }
		DefaultCacheBehavior = @{
			TargetOriginId = 's3-covers'
			ViewerProtocolPolicy = 'https-only'
			CachePolicyId = '658327ea-f89d-4fab-a63d-7e88639e58f6'
			Compress = $false
			AllowedMethods = @{ Quantity = 2; Items = @('GET', 'HEAD'); CachedMethods = @{ Quantity = 2; Items = @('GET', 'HEAD') } }
		}
	}
	$dist = (Invoke-Aws @('cloudfront', 'create-distribution', '--distribution-config', $config)).Distribution
	Write-Host "distribution made"
}
$distId = $dist.Id
$domain = $dist.DomainName
$distArn = "arn:aws:cloudfront::${account}:distribution/$distId"

# 4. The bucket policy: only that distribution may read.
$policy = Write-Json 'bucket-policy.json' @{
	Version = '2012-10-17'
	Statement = @(@{
		Sid = 'CloudFrontReadsCovers'; Effect = 'Allow'
		Principal = @{ Service = 'cloudfront.amazonaws.com' }
		Action = 's3:GetObject'; Resource = "arn:aws:s3:::$bucket/*"
		Condition = @{ StringEquals = @{ 'AWS:SourceArn' = $distArn } }
	})
}
Invoke-Aws @('s3api', 'put-bucket-policy', '--bucket', $bucket, '--policy', $policy) | Out-Null

# 5. The PC upload user: put objects and list the bucket, nothing else.
$user = Invoke-Aws @('iam', 'get-user', '--user-name', $UploaderName) -AllowFail
if (-not $user) {
	Invoke-Aws @('iam', 'create-user', '--user-name', $UploaderName) | Out-Null
	Write-Host "IAM user made"
}
$userPolicy = Write-Json 'uploader-policy.json' @{
	Version = '2012-10-17'
	Statement = @(
		@{ Sid = 'PutCovers'; Effect = 'Allow'; Action = 's3:PutObject'; Resource = "arn:aws:s3:::$bucket/*" },
		@{ Sid = 'ListCovers'; Effect = 'Allow'; Action = 's3:ListBucket'; Resource = "arn:aws:s3:::$bucket" }
	)
}
Invoke-Aws @('iam', 'put-user-policy', '--user-name', $UploaderName, '--policy-name', 'covers-upload',
	'--policy-document', $userPolicy) | Out-Null

$keys = (Invoke-Aws @('iam', 'list-access-keys', '--user-name', $UploaderName)).AccessKeyMetadata
$configured = & $Aws configure get aws_access_key_id --profile $UploadProfile 2>$null
if (-not ($keys | Where-Object { $_.AccessKeyId -eq $configured })) {
	if (@($keys).Count -ge 2) { throw "$UploaderName already has two keys and none is in profile $UploadProfile" }
	$key = (Invoke-Aws @('iam', 'create-access-key', '--user-name', $UploaderName)).AccessKey
	& $Aws configure set aws_access_key_id $key.AccessKeyId --profile $UploadProfile
	& $Aws configure set aws_secret_access_key $key.SecretAccessKey --profile $UploadProfile
	& $Aws configure set region $Region --profile $UploadProfile
	$key = $null
	Write-Host "upload key stored in profile $UploadProfile"
}

Remove-Item -Recurse -Force $work
$state = [ordered]@{ account = $account; region = $Region; bucket = $bucket; distributionId = $distId
	domain = $domain; baseUrl = "https://$domain"; uploadProfile = $UploadProfile; uploader = $UploaderName }
$statePath = Join-Path $PSScriptRoot '..\..\data\covers-aws.json'
New-Item -ItemType Directory -Force (Split-Path $statePath) | Out-Null
[IO.File]::WriteAllText($statePath, ($state | ConvertTo-Json), (New-Object Text.UTF8Encoding $false))
$state | Format-List
