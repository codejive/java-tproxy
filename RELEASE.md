# Release Process

This project uses [JReleaser](https://jreleaser.org/) for automated releases to GitHub and Maven Central.

## Prerequisites

Ensure GitHub secrets are configured (see First-Time Setup below if not already configured).

## Creating a Release

1. **Ensure all changes are committed and pushed**

   ```bash
   git status  # Should be clean
   ```

2. **Trigger the release workflow**
   - Go to [Actions → Release](../../actions/workflows/release.yml)
   - Click **Run workflow**
   - Enter the release version (e.g., `1.0.0`)
   - Click **Run workflow**

3. **Monitor the workflow**
   - The workflow will:
     - Update version in pom.xml
     - Build and sign artifacts
     - Deploy to Maven Central
     - Create GitHub release with changelog
     - Bump to next SNAPSHOT version

4. **Verify the release**
   - Check [GitHub Releases](../../releases)
   - Check Maven Central (may take ~30 minutes)

## Local Testing (Optional)

Test the configuration before releasing:

```bash
# Validate configuration
./mvnw jreleaser:config

# Test build with release profile
./mvnw clean install -Prelease

# Check assembled artifacts
ls -la target/staging-deploy/
```

## First-Time Setup

### 1. Generate GPG Keys

```bash
# Generate key
gpg --gen-key

# Get key ID
gpg --list-secret-keys --keyid-format=long

# Export keys
gpg --armor --export YOUR_KEY_ID > public.key
gpg --armor --export-secret-keys YOUR_KEY_ID > private.key

# Publish to key server
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
```

### 2. Register at Maven Central

1. Sign up at https://central.sonatype.com/
2. Verify ownership of your namespace (e.g., `org.codejive.tproxy`)
3. Get username and password/token

### 3. Configure GitHub Secrets

Add these secrets at [Settings → Secrets and variables → Actions](../../settings/secrets/actions):

| Secret Name | Description | How to Get It |
| ----------- | ----------- | ------------- |
| `GPG_PUBLIC_KEY` | Your GPG public key | Copy entire contents of `public.key` file |
| `GPG_SECRET_KEY` | Your GPG private key | Copy entire contents of `private.key` file |
| `GPG_PASSPHRASE` | Passphrase for your GPG key | The passphrase you entered when generating the key |
| `MAVENCENTRAL_USERNAME` | Maven Central username | From your Maven Central account token |
| `MAVENCENTRAL_PASSWORD` | Maven Central password | From your Maven Central account token |

**Note:** The `GITHUB_TOKEN` is automatically provided by GitHub Actions and doesn't need to be configured.

### 4. Optional: Create a Protected Environment

For additional security, you can create a protected environment:

1. Go to Settings → Environments
2. Click "New environment"
3. Name it `jreleaser`
4. Add protection rules:
   - ✅ Required reviewers (recommended for production releases)
   - ✅ Wait timer (optional delay before deployment)
5. Add the same secrets to this environment

Then uncomment this line in `.github/workflows/release.yml`:

```yaml
# environment: jreleaser
```

## Version Management

- Development versions use `-SNAPSHOT` suffix (e.g., `1.0.0-SNAPSHOT`)
- Release versions have no suffix (e.g., `1.0.0`)
- The workflow automatically bumps to next SNAPSHOT after release
- Use [semantic versioning](https://semver.org/): MAJOR.MINOR.PATCH
