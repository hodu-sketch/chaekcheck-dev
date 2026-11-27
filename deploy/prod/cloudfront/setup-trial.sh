#!/usr/bin/env bash
# CloudFront trial (user 2026-11-26): puts Amazon CloudFront in front of the production server on a test name
# (cf-test.chaekcheck.com) next to the Cloudflare way in, which stays as it is. Korea reaches Cloudflare in Los Angeles
# on the free plan; CloudFront's edge for Korea is in Seoul. Steps and rollback: docs/maintenance/cloudfront-trial.md.
#
# Run in the AWS console's CloudShell (any region; the script names its regions). Safe to run again: what exists is
# reused. It stops twice for a step only the user can do in the Cloudflare dashboard:
#   1. the certificate's DNS check record  2. the cf-test name pointing at CloudFront
# It never touches chaekcheck.com itself, the tunnel, or Cloudflare.
#
#   bash setup-trial.sh            # create or check everything, print what is left
#   bash setup-trial.sh --status   # print the state only
set -euo pipefail

REGION=ap-southeast-2                 # the production server (Sydney)
PROD_SG=sg-0f424434c56c5d021          # the production server's security group (docs/maintenance/ip-change-checklist.md)
DOMAIN=chaekcheck.com
TEST_HOST=cf-test.chaekcheck.com
PORT=8089                             # deploy/nginx/ec2-tunnel.conf, the CloudFront server block
VPC_ORIGIN_NAME=chaekcheck-web-8089
COMMENT="chaekcheck CloudFront trial (cf-test)"
# AWS managed policies (the same ids in every account)
CACHING_DISABLED=4135ea2d-6df8-44a3-9df3-4b5a84be39ad
CACHING_OPTIMIZED=658327ea-f89d-4fab-a63d-7e88639e58f6
ALL_VIEWER=216adef6-5c7f-47e4-b989-5492eafa07d3

STATUS_ONLY=false
[ "${1:-}" = "--status" ] && STATUS_ONLY=true

say() { printf '\n== %s\n' "$*"; }

ACCOUNT=$(aws sts get-caller-identity --query Account --output text)

say "1/5 production server"
read -r INSTANCE VPC PRIVATE_DNS < <(aws ec2 describe-instances --region "$REGION" \
	--filters "Name=instance.group-id,Values=$PROD_SG" "Name=instance-state-name,Values=running" \
	--query 'Reservations[].Instances[].[InstanceId,VpcId,PrivateDnsName]' --output text)
[ -n "${INSTANCE:-}" ] || { echo "no running instance in $PROD_SG"; exit 1; }
echo "instance $INSTANCE, vpc $VPC"

say "2/5 certificate (us-east-1, $DOMAIN and *.$DOMAIN, DNS check)"
CERT=$(aws acm list-certificates --region us-east-1 --includes keyTypes=RSA_2048 \
	--query "CertificateSummaryList[?DomainName=='$DOMAIN'].CertificateArn | [0]" --output text)
if [ "$CERT" = "None" ] || [ -z "$CERT" ]; then
	$STATUS_ONLY && { echo "no certificate yet"; exit 0; }
	CERT=$(aws acm request-certificate --region us-east-1 --domain-name "$DOMAIN" \
		--subject-alternative-names "*.$DOMAIN" --validation-method DNS --query CertificateArn --output text)
	echo "requested $CERT"
	sleep 10
fi
CERT_STATUS=$(aws acm describe-certificate --region us-east-1 --certificate-arn "$CERT" \
	--query Certificate.Status --output text)
echo "certificate $CERT_STATUS"
if [ "$CERT_STATUS" != "ISSUED" ]; then
	echo
	echo "Cloudflare dashboard > $DOMAIN > DNS > Add record, then run this script again (usually issued in minutes):"
	aws acm describe-certificate --region us-east-1 --certificate-arn "$CERT" \
		--query 'Certificate.DomainValidationOptions[0].ResourceRecord.[Type,Name,Value]' --output text |
		while read -r type name value; do
			echo "  Type $type   Name ${name%.}   Target ${value%.}   Proxy status: DNS only (grey cloud)"
		done
	exit 0
fi

say "3/5 VPC origin ($VPC_ORIGIN_NAME -> $INSTANCE:$PORT, http inside the VPC)"
VPC_ORIGIN=$(aws cloudfront list-vpc-origins \
	--query "VpcOriginList.Items[?Name=='$VPC_ORIGIN_NAME'].Id | [0]" --output text 2>/dev/null || echo None)
if [ "$VPC_ORIGIN" = "None" ] || [ -z "$VPC_ORIGIN" ]; then
	$STATUS_ONLY && { echo "no VPC origin yet"; exit 0; }
	VPC_ORIGIN=$(aws cloudfront create-vpc-origin --vpc-origin-endpoint-config "$(jq -nc \
		--arg name "$VPC_ORIGIN_NAME" --arg arn "arn:aws:ec2:$REGION:$ACCOUNT:instance/$INSTANCE" --argjson port "$PORT" \
		'{Name:$name, Arn:$arn, HTTPPort:$port, HTTPSPort:443, OriginProtocolPolicy:"http-only",
		  OriginSslProtocols:{Quantity:1, Items:["TLSv1.2"]}}')" --query VpcOrigin.Id --output text)
	echo "created $VPC_ORIGIN"
fi
for _ in $(seq 1 60); do
	ORIGIN_STATUS=$(aws cloudfront get-vpc-origin --id "$VPC_ORIGIN" --query VpcOrigin.Status --output text)
	[ "$ORIGIN_STATUS" = "Deployed" ] && break
	$STATUS_ONLY && break
	echo "  $ORIGIN_STATUS, waiting (often 10-15 min)"
	sleep 30
done
echo "VPC origin $VPC_ORIGIN $ORIGIN_STATUS"
[ "$ORIGIN_STATUS" = "Deployed" ] || { echo "not deployed yet: run again later"; exit 0; }

# CloudFront puts its own group in the VPC; only that group may reach the port
CF_SG=$(aws ec2 describe-security-groups --region "$REGION" \
	--filters "Name=vpc-id,Values=$VPC" "Name=group-name,Values=CloudFront-VPCOrigins-Service-SG" \
	--query 'SecurityGroups[0].GroupId' --output text)
echo "CloudFront group $CF_SG"
if ! $STATUS_ONLY; then
	aws ec2 authorize-security-group-ingress --region "$REGION" --group-id "$PROD_SG" \
		--ip-permissions "IpProtocol=tcp,FromPort=$PORT,ToPort=$PORT,UserIdGroupPairs=[{GroupId=$CF_SG,Description=CloudFront VPC origin trial}]" \
		>/dev/null 2>&1 && echo "allowed $PORT from $CF_SG" || echo "rule for $PORT from $CF_SG already there"
fi

say "4/5 distribution ($TEST_HOST)"
DIST=$(aws cloudfront list-distributions \
	--query "DistributionList.Items[?Aliases.Items && contains(Aliases.Items, '$TEST_HOST')].Id | [0]" --output text)
if [ "$DIST" = "None" ] || [ -z "$DIST" ]; then
	$STATUS_ONLY && { echo "no distribution yet"; exit 0; }
	behaviour() { # $1 cache policy
		jq -nc --arg cache "$1" --arg viewer "$ALL_VIEWER" '{TargetOriginId:"chaekcheck-web",
			ViewerProtocolPolicy:"redirect-to-https", Compress:false, CachePolicyId:$cache, OriginRequestPolicyId:$viewer,
			AllowedMethods:{Quantity:7, Items:["GET","HEAD","OPTIONS","PUT","POST","PATCH","DELETE"],
				CachedMethods:{Quantity:2, Items:["GET","HEAD"]}}}'
	}
	CONFIG=$(jq -nc --arg ref "chaekcheck-cf-trial-$(date +%s)" --arg host "$TEST_HOST" --arg cert "$CERT" \
		--arg dns "$PRIVATE_DNS" --arg origin "$VPC_ORIGIN" --arg comment "$COMMENT" \
		--argjson def "$(behaviour "$CACHING_DISABLED")" --argjson assets "$(behaviour "$CACHING_OPTIMIZED")" '{
		CallerReference:$ref, Comment:$comment, Enabled:true, HttpVersion:"http2and3", IsIPV6Enabled:true,
		PriceClass:"PriceClass_200",
		Aliases:{Quantity:1, Items:[$host]},
		ViewerCertificate:{ACMCertificateArn:$cert, SSLSupportMethod:"sni-only", MinimumProtocolVersion:"TLSv1.2_2021"},
		Origins:{Quantity:1, Items:[{Id:"chaekcheck-web", DomainName:$dns, OriginPath:"",
			CustomHeaders:{Quantity:0},
			VpcOriginConfig:{VpcOriginId:$origin, OriginReadTimeout:60, OriginKeepaliveTimeout:60}}]},
		DefaultCacheBehavior:$def,
		CacheBehaviors:{Quantity:1, Items:[($assets + {PathPattern:"/assets/*", Compress:true})]}}')
	DIST=$(aws cloudfront create-distribution --distribution-config "$CONFIG" --query Distribution.Id --output text)
	echo "created $DIST"
fi
read -r DIST_DOMAIN DIST_STATUS < <(aws cloudfront get-distribution --id "$DIST" \
	--query 'Distribution.[DomainName,Status]' --output text)
echo "distribution $DIST $DIST_DOMAIN $DIST_STATUS (Deployed takes a few minutes)"

say "5/5 the test name"
echo "Cloudflare dashboard > $DOMAIN > DNS > Add record (only this name; chaekcheck.com stays on Cloudflare):"
echo "  Type CNAME   Name cf-test   Target $DIST_DOMAIN   Proxy status: DNS only (grey cloud)"
echo
echo "Then from the PC: curl -sI https://$TEST_HOST/api/v1/version | grep -i x-amz-cf-pop   (ICN = Seoul)"
