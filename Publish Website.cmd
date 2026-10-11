@echo off
rem Publishes website\scamcatch\public to scamcatch.io. Commands come from website\scamcatch\infra\README.txt.
cd /d "%~dp0"
echo Uploading website files to scamcatch.io...
call aws s3 sync ./website/scamcatch/public s3://scamcatch-site-686458136219/ --cache-control public,max-age=300 --profile mangorts-dev --region us-east-1
if errorlevel 1 goto failed
echo.
echo Clearing the CloudFront cache...
call aws cloudfront create-invalidation --distribution-id E1FTFPRB048HY --paths "/*" --profile mangorts-dev --output text --query "Invalidation.[Id,Status]"
if errorlevel 1 goto failed
echo.
echo PUBLISHED. scamcatch.io shows the new version within a few minutes.
pause
exit /b 0
:failed
echo.
echo PUBLISH FAILED. Read the error above.
pause
exit /b 1
