#!/usr/bin/env bash
# Cycle 26 step 1 (ADR-0163) from the AWS console's CloudShell (region Sydney), the same pieces as setup-aws.ps1 except
# the upload key: a private bucket, CloudFront with OAC on its default domain, a bucket policy for that distribution
# only, and the IAM user that may only put and list covers. Paste it whole into CloudShell; it prints the names at the
# end. The upload key is made later on the PC (setup-aws.ps1, or the IAM console), so no secret shows here.
set -euo pipefail
REGION=ap-southeast-2
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
BUCKET=chaekcheck-covers-$ACCOUNT
UPLOADER=chaekcheck-covers-uploader
cd "$(mktemp -d)"

aws s3api create-bucket --bucket "$BUCKET" --region "$REGION" \
  --create-bucket-configuration LocationConstraint="$REGION"
aws s3api put-public-access-block --bucket "$BUCKET" --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true

OAC_ID=$(aws cloudfront create-origin-access-control --origin-access-control-config \
  "Name=$BUCKET-oac,Description=chaekcheck-covers,SigningProtocol=sigv4,SigningBehavior=always,OriginAccessControlOriginType=s3" \
  --query OriginAccessControl.Id --output text)

cat > distribution.json <<EOF
{
  "CallerReference": "chaekcheck-covers-$(date +%s)",
  "Comment": "chaekcheck covers",
  "Enabled": true,
  "PriceClass": "PriceClass_200",
  "HttpVersion": "http2and3",
  "IsIPV6Enabled": true,
  "Origins": { "Quantity": 1, "Items": [ {
    "Id": "s3-covers",
    "DomainName": "$BUCKET.s3.$REGION.amazonaws.com",
    "OriginAccessControlId": "$OAC_ID",
    "S3OriginConfig": { "OriginAccessIdentity": "" }
  } ] },
  "DefaultCacheBehavior": {
    "TargetOriginId": "s3-covers",
    "ViewerProtocolPolicy": "https-only",
    "CachePolicyId": "658327ea-f89d-4fab-a63d-7e88639e58f6",
    "Compress": false,
    "AllowedMethods": { "Quantity": 2, "Items": ["GET", "HEAD"],
      "CachedMethods": { "Quantity": 2, "Items": ["GET", "HEAD"] } }
  }
}
EOF
read -r DIST_ID DOMAIN < <(aws cloudfront create-distribution --distribution-config file://distribution.json \
  --query '[Distribution.Id, Distribution.DomainName]' --output text)

cat > bucket-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [ {
    "Sid": "CloudFrontReadsCovers",
    "Effect": "Allow",
    "Principal": { "Service": "cloudfront.amazonaws.com" },
    "Action": "s3:GetObject",
    "Resource": "arn:aws:s3:::$BUCKET/*",
    "Condition": { "StringEquals": { "AWS:SourceArn": "arn:aws:cloudfront::$ACCOUNT:distribution/$DIST_ID" } }
  } ]
}
EOF
aws s3api put-bucket-policy --bucket "$BUCKET" --policy file://bucket-policy.json

aws iam create-user --user-name "$UPLOADER" > /dev/null
cat > uploader-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    { "Sid": "PutCovers", "Effect": "Allow", "Action": "s3:PutObject", "Resource": "arn:aws:s3:::$BUCKET/*" },
    { "Sid": "ListCovers", "Effect": "Allow", "Action": "s3:ListBucket", "Resource": "arn:aws:s3:::$BUCKET" }
  ]
}
EOF
aws iam put-user-policy --user-name "$UPLOADER" --policy-name covers-upload \
  --policy-document file://uploader-policy.json

echo "bucket=$BUCKET"
echo "distribution=$DIST_ID"
echo "domain=$DOMAIN"
echo "uploader=$UPLOADER (no key yet)"
