# AWS setup (S3 guest wait board)

Turn runs in the restaurant. AWS holds one S3 bucket that serves the guest page and the live wait board to guests' phones.

## 1. Create the bucket

1. In the S3 console, choose **Create bucket**.
2. Set these options:
   - **Name:** for example `turn-wait-<yourname>`
   - **Region:** `us-east-1`, or set `turn.aws.region` to match
3. Under **Block Public Access**, untick **Block all public access** and confirm. Only `public/*` is opened in the next step.
4. Choose **Create**.
5. In the new bucket, go to **Permissions → Bucket policy** and paste this (use your bucket name):

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Sid": "PublicGuestBoard",
    "Effect": "Allow",
    "Principal": "*",
    "Action": "s3:GetObject",
    "Resource": "arn:aws:s3:::turn-wait-<yourname>/public/*"
  }]
}
```

`shifts/` stays private.

## 2. Give Turn a key that can only write to that bucket

1. In IAM, create a user called `turn-publisher`.
2. Attach this inline policy:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": "s3:PutObject",
    "Resource": [
      "arn:aws:s3:::turn-wait-<yourname>/public/*",
      "arn:aws:s3:::turn-wait-<yourname>/shifts/*"
    ]
  }]
}
```

3. Create an access key for the user.
4. Put the key in the environment of the shell that runs Turn. Never put it in `application.yml` or commit it.

```
set AWS_ACCESS_KEY_ID=...
set AWS_SECRET_ACCESS_KEY=...
```

## 3. Run Turn with the bucket

In PowerShell, use `.\mvnw` and quote each `-D...` argument; the commands below are for cmd.

```
mvnw spring-boot:run -Dspring-boot.run.profiles=dining -Dspring-boot.run.arguments="--turn.aws.bucket=turn-wait-<yourname> --server.port=8097"
```

The log should say `Cloud sync on: s3://turn-wait-<yourname>/public/ every 10 s`. `/api/health` shows the last sync time.

The guest page is then live at:

```
https://turn-wait-<yourname>.s3.amazonaws.com/public/index.html
```

You can make it a QR code on the host stand.

## 4. Hosted demo on EC2 (CloudFormation)

This gives anyone, including judges, a link that works when the restaurant laptop is off. It runs Turn's scripted demo shift on one small EC2 instance and publishes its own guest board to `public/demo/`.

1. Build the jar. This writes `target/turn.jar`:
   ```
   mvnw package -DskipTests
   ```
2. Upload the jar.
   - In the S3 console, open the bucket and click **Create folder**. Name it `releases`.
   - Open `releases`, click **Upload**, add `target/turn.jar`, then click **Upload**.
3. Create the stack.
   - Open **CloudFormation** and choose **Create stack → With new resources**.
   - Pick **Upload a template file**, choose `infra/turn-demo.yaml`, and click **Next**.
   - Stack name: `turn-demo`. Check that BucketName is your bucket, and leave the rest as it is. Click **Next**, then **Next** again.
   - At the bottom, tick **"I acknowledge that AWS CloudFormation might create IAM resources"**, then click **Submit**.
4. Wait for **CREATE_COMPLETE**, about 3 to 6 minutes. The stack waits until Turn answers its health check.
5. Open the **Outputs** tab:
   - `DemoUrl` is the hosted Floor.
   - `HealthUrl` is the health check.
   - `DemoGuestPage` is the demo's S3 guest page.

To update the demo, upload the new `turn.jar`, then delete the stack and create it again. The jar is downloaded only on the instance's first boot.

To remove everything, delete the stack. The bucket stays.

## What it costs

S3 charges per request. Two writes every 10 s is about 520,000 PUTs a month if left on around the clock, so turn it off between shifts or raise `turn.aws.every-sec`. Check current S3 pricing for your region.

The EC2 demo is one t3.micro (free tier eligible on new accounts) plus its S3 writes. Delete the stack when judging is over.
