"""Source-level guard for destructive refresh-error handling; no Android SDK needed.

Run: python3 scripts/check_auth_failure.py
This checks the WebView auth contract, not Android or HTTP behavior.
"""

from pathlib import Path


source = (
    Path(__file__).resolve().parents[1]
    / "app/src/main/java/com/msp1974/vacompanion/utils/CustomWebView.kt"
).read_text()


def section(start, end):
    assert source.count(start) == 1, f"Update check: ambiguous/missing {start}"
    return source.split(start, 1)[1].split(end, 1)[0]


request = section("suspend fun requestAuthorisation(", "private suspend fun safeRevokeSession(")
failure = request.split("catch (ex: AuthenticationException)", 1)[1].split(
    "catch (ex: Exception)", 1
)[0]
assert "safeRevokeSession()" not in request, "Refresh failure must not revoke a session"
assert "revokeSession()" not in failure, "Refresh failure must not revoke a session"
assert "reload()" not in failure, "Refresh failure must not trigger a reload loop"
assert "callAuthJS(view, false)" in failure, "The frontend must receive a failure"
assert "callAuthJS(view, true)" not in failure, "Do not return a stale token as success"
assert 'if (config.refreshToken.isBlank())' in failure
assert "view.loadUrl(deviceManager.authenticationManager.getExternalAuthUrl())" in failure

logout = section("override fun onRequestRevokeExternalAuth(", "private fun callAuthJS(")
assert "safeRevokeSession()" in logout, "Explicit logout must still request revocation"
print("Auth failure source contract passed (not a device/integration test)")
