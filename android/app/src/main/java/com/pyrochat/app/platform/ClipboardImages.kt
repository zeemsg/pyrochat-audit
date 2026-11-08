package com.pyrochat.app.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * The image half of `PastingTextView` (`ios/PyroChat/UI/PasteComposer.swift:9-32`): when the
 * clipboard holds an image and NO text, a paste becomes an attachment (normalised → EXIF
 * stripped → queued for `[TX]`); when text is present the paste stays a normal text paste.
 * iOS looks at `public.png` / `public.jpeg` / `public.heic` / `com.compuserve.gif`, then any
 * `UIImage`; here any `image/…` MIME on the clip, or a content Uri whose provider says `image/…`.
 *
 * Android 10+ only hands the clipboard to the focused app: call this from the composer's paste
 * action, never from the background (the manager returns nothing there).
 */
class ClipboardImages(context: Context) {
    private val appContext = context.applicationContext
    private val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** `UIPasteboard.general.hasImages` */
    fun hasImage(): Boolean = imageUriOrNull() != null || descriptionHasImage()

    /** `UIPasteboard.general.hasStrings` */
    fun hasText(): Boolean {
        val clip = clipboard.primaryClip ?: return false
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            if (!item.text.isNullOrEmpty() || !item.htmlText.isNullOrEmpty()) return true
        }
        return false
    }

    /** `pb.hasImages && !pb.hasStrings` — intercept the paste as an attachment. */
    fun shouldInterceptPaste(): Boolean = hasImage() && !hasText()

    /** Normalised JPEG of the clipboard image (`onImagePaste` → `ImageNormalizer.normalize`), or null. */
    fun pastedImage(): ImageNormalizer.Normalized? {
        val uri = imageUriOrNull() ?: return null
        return try {
            ImageNormalizer.normalize(appContext.contentResolver, uri)
        } catch (_: SecurityException) {
            null
        }
    }

    private fun descriptionHasImage(): Boolean {
        val d = clipboard.primaryClipDescription ?: return false
        for (i in 0 until d.mimeTypeCount) if (d.getMimeType(i).startsWith("image/")) return true
        return false
    }

    private fun imageUriOrNull(): android.net.Uri? {
        val clip: ClipData = clipboard.primaryClip ?: return null
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i).uri ?: continue
            val type = try {
                appContext.contentResolver.getType(uri)
            } catch (_: SecurityException) {
                null
            }
            if (type?.startsWith("image/") == true) return uri
            if (type == null && descriptionHasImage()) return uri
        }
        return null
    }
}
