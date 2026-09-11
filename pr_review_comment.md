**Code Review**

I have reviewed the PR, and while the `version.properties` bump looks correct and works as intended, the `local.properties` file mentioned in the PR description is missing from the commit diff.

**Issues:**
1. **Missing File**: `local.properties` was not included in the commit because it is listed in `.gitignore`.
2. **Best Practice**: `local.properties` contains machine-specific absolute paths, so it is meant to be ignored by source control anyway. Committing it would break local builds for other developers whose SDKs are located elsewhere (e.g. `/Users/name/Library/Android/sdk` on macOS or `C:\Users\name\AppData\Local\Android\Sdk` on Windows).

**Resolution:**
The correct way to handle remote CI environments or container builds is to generate `local.properties` during the environment setup rather than committing it to the repository.

I will open a follow-up PR to fix this issue by modifying the `scripts/setup-android-env.sh` script to explicitly write the generated `ANDROID_HOME` path into `local.properties`. This correctly configures the SDK path for the build without polluting the source control history with machine-specific files.
