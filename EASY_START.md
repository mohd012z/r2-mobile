# R2 Mobile: get the APK (easy mode)

You do not need to install anything on your computer.

## Step 1: Put the folder on GitHub

1. Go to https://github.com/new and create a repository named `r2-mobile`. Set it to **Private**.
2. Click **uploading an existing file**.
3. Drag everything inside the `r2-android` folder into the page (including the hidden `.github` folder and `.gitignore`).
4. Click **Commit changes**.

Note: if your browser hides the `.github` folder, select the folder itself rather than its contents.

## Step 2: Wait for the build

1. Open the **Actions** tab of your repo.
2. Click **Build APK**, then wait about 10 to 20 minutes. It shows a green tick when done.

## Step 3: Download the APK

1. Click the finished run.
2. Scroll to **Artifacts** and download `r2-mobile-debug-apk`.
3. Unzip it. You get `app-debug.apk`.

## Step 4: Install on the Poco F6 Pro

1. Copy `app-debug.apk` to the phone.
2. Open it. If asked, allow "Install unknown apps" for your file manager.
3. Open **R2 Mobile**, tap **Open binary**, pick any file, and type `i` as the command, then tap **Run**. You should see file info.

## If the build goes red

Click the failed step in the Actions run, copy the last 30 lines of red text, and send them to me. Do not try to fix it yourself.

## Notes

- The app is for the arm64 Poco F6 Pro only.
- This build has not been run yet. The workflow is unverified until the first run.
