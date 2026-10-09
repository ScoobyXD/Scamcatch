# DEV AWS cost changes

1. Account `686458136219`, region `us-west-1`: the user approved the public-IP network migration and chose to keep MangoRTS online. No shutdown schedule is required.
2. `mangorts-dev-backend` keeps desired count one. `mangorts-dev-email-worker` and `ctrlloop-mvp` each have zero desired, running, and pending tasks. No restart schedule was created.
3. All three services and the daily attachment-maintenance target now use public subnets `subnet-00a38a8cafdcd4f6b` / `subnet-0c765c34c59978b0f` with public IP assignment and unchanged security groups.
4. The network migration passed backend HTTP 200 and ALB health checks. The old private task drained completely. Backend revision `234` and worker revision `53` completed deployment. Final counts are backend one and both paused services zero.
5. Task definitions are backend `234`, email worker `53`, and maintenance `12`. Each sets `DB_CONNECT_TIMEOUT_SECONDS=60`. Backend health-check grace is 180 seconds.
6. NAT `nat-062748df75bbac4e7` is confirmed deleted. No routes to it remain. EIP `eipalloc-0adad95b1c455c16f` was released and is absent from the address lookup.
7. Valkey, private subnets `subnet-0c96aa9cdb41d46f7` / `subnet-0d7cdf9a8970301c1`, their route tables, and local routes remain. Preserve their private placement.
8. Aurora uses minimum 0 ACU, maximum 1 ACU, and 900-second auto-pause. Actual database pause and database savings remain unconfirmed. Container Insights is disabled, with alarm settings unchanged.
9. Cost Explorer rates imply about $35.04/month less NAT running cost, excluding traffic. Both paused services save about $22.59/month in compute if they remain stopped. Estimates use 730 hours and exclude other charges.
10. The backend public IP replaces the released NAT EIP's hourly address charge. Maintenance adds brief public-IP time.
11. GitHub `TF_ECS_NAT_GATEWAY_MODE` is verified `none`. Published MangoRTS source now accepts `none`. The DEV deployment workflow remains manual-dispatch only.
12. The 12-file cost package was published to MangoRTSDev `main` as `116b9943876c3029df775052aee603bc8cc904ab`. Its workflow and rules files are `.github/workflows/deploy-dev.yml` and `docs/tests/dev-deployment.md`.
13. Its DEV files are `infra/aws-dev/main.tf`, `infra/aws-dev/variables.tf`, and `infra/aws-dev/terraform.tfvars.example`.
14. Its shared-module files are `variables.tf`, `locals.tf`, `networking.tf`, `ecs_alb.tf`, `email_sqs.tf`, `attachment_maintenance.tf`, and `realtime_valkey.tf`, all under `infra/modules/mangorts-platform/`.
15. The existing local `infra/aws-dev/terraform.tfvars` also uses `none`. Its hidden attribute and ACLs remain unchanged. Preserve all unrelated local edits.
16. CTRL Loop network, pause, and Aurora-startup changes were published to `main` as `f44422d05eaa311cef1b720eda015ee8e11ff542`. The three-file commit excludes unrelated CPU/memory increases. Its dirty local checkout remains unchanged at `e39b894`, five commits behind GitHub.
17. Preserve the four original CloudShell backups dated `20261008`, plus `aws-nat-migration-before-20261009.json`, `aws-db-startup-before-20261009.json`, `aws-db-startup-after-20261009.json`, and the verified `aws-cost-cut-final-20261009.json` report.
18. Network rollback now requires a replacement NAT gateway and a new EIP. Restore both private default routes before restoring private task placement and the maintenance target. Keep paused services at zero.
19. The email dead-letter queue contains 32 previously failed jobs. Queue retention is 14 days, so queued jobs can expire while processing remains paused. No queue was purged.
20. The user authorized source publication. Both main commits use `[skip ci]`. GitHub reports no MangoRTS workflow runs for its commit, and CTRL Loop has no Actions workflows or repository webhooks. No AWS deployment or tests ran during publication. HCL, Python, and JSON parsing passed during implementation.
21. The reviewed Fargate retirement notice requires no manual action for the running ECS service. Aurora maintenance is scheduled for October 27, 2026, at 5:01 a.m. Pacific. The November snapshot notice changes cost attribution, not prices.
