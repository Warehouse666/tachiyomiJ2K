package eu.kanade.tachiyomi.ui.setting.track

import android.net.Uri
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.util.system.launchIO

class MangaBakaLoginActivity : BaseOAuthLoginActivity() {
    override fun handleResult(data: Uri?) {
        val state = data?.getQueryParameter("state")
        val code = data?.getQueryParameter("code")
        if (code != null && state != null && trackManager.mangaBaka.verifyOAuthState(state)) {
            lifecycleScope.launchIO {
                trackManager.mangaBaka.login(code)
                returnToSettings()
            }
        } else {
            trackManager.mangaBaka.logout()
            returnToSettings()
        }
    }
}
