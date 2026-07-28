package eu.kanade.tachiyomi.ui.setting.track

import android.net.Uri
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.util.system.launchIO

class HikkaLoginActivity : BaseOAuthLoginActivity() {
    override fun handleResult(data: Uri?) {
        val reference = data?.getQueryParameter("reference")
        if (reference != null) {
            lifecycleScope.launchIO {
                trackManager.hikka.login(reference)
                returnToSettings()
            }
        } else {
            trackManager.hikka.logout()
            returnToSettings()
        }
    }
}
