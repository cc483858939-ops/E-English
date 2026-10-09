package com.eenglish.listening.navigation

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts

/** Keep custom-extension documents visible and restrict the picker to streamable files. */
class OfflinePackDocuments : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE)
}
