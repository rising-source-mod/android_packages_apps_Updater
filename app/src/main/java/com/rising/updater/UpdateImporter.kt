/*
 * Copyright (C) 2017-2022 The LineageOS Project
 * Copyright (C) 2020-2022 SHIFT GmbH
 * Copyright (C) 2024 RisingOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.rising.updater

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.rising.updater.controller.UpdaterController
import com.rising.updater.controller.UpdaterService
import com.rising.updater.misc.StringGenerator
import com.rising.updater.misc.Utils
import com.rising.updater.model.Update
import com.rising.updater.model.UpdateStatus
import java.io.*
import java.nio.charset.StandardCharsets
import java.text.DateFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class UpdateImporter(private val activity: UpdatesActivity, private val callbacks: Callbacks) {
    private var workingThread: Thread? = null

    fun stopImport() {
        if (workingThread != null && workingThread!!.isAlive) {
            workingThread!!.interrupt()
            workingThread = null
        }
    }

    fun openImportPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(MIME_ZIP)
        activity.startActivityForResult(intent, REQUEST_PICK)
    }

    fun onResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK || requestCode != REQUEST_PICK) {
            return false
        }

        return onPicked(data!!.data!!)
    }

    private fun onPicked(uri: Uri): Boolean {
        callbacks.onImportStarted()

        workingThread = Thread {
            var importedFile: File? = null
            try {
                importedFile = importFile(uri)
                verifyPackage(importedFile)

                val update = buildLocalUpdate(importedFile)
                addUpdate(update)
                val intent = Intent(activity, UpdaterService::class.java)
                intent.action = UpdaterService.ACTION_INSTALL_UPDATE
                intent.putExtra(UpdaterService.EXTRA_DOWNLOAD_ID, Update.LOCAL_ID)
                ContextCompat.startForegroundService(activity, intent)
                activity.runOnUiThread { callbacks.onImportCompleted(update) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import update package", e)
                // Do not store invalid update
                importedFile?.delete()
                activity.runOnUiThread { callbacks.onImportCompleted(null) }
                showSnackbar(R.string.local_update_import_failure)
            }
        }
        workingThread!!.start()
        return true
    }

    @SuppressLint("SetWorldReadable")
    @Throws(IOException::class)
    private fun importFile(uri: Uri): File {
        val parcelDescriptor = activity.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Failed to obtain fileDescriptor")

        val iStream = FileInputStream(parcelDescriptor.fileDescriptor)
        val downloadDir = Utils.getDownloadPath(activity)
        val outFile = File(downloadDir, FILE_NAME)
        if (outFile.exists()) {
            outFile.delete()
        }
        val oStream = FileOutputStream(outFile)

        var read: Int
        val buffer = ByteArray(4096)
        while (iStream.read(buffer).also { read = it } > 0) {
            oStream.write(buffer, 0, read)
        }
        oStream.flush()
        oStream.close()
        iStream.close()
        parcelDescriptor.close()

        outFile.setReadable(true, false)

        return outFile
    }

    private fun buildLocalUpdate(file: File): Update {
        val timeStamp = getTimeStamp(file)
        val buildDate = StringGenerator.getDateLocalizedUTC(activity, DateFormat.MEDIUM, timeStamp)
        val name = activity.getString(R.string.local_update_name)
        return Update().apply {
            setAvailableOnline(false)
            setName(name)
            setFile(file)
            setFileSize(file.length())
            setDownloadId(Update.LOCAL_ID)
            setTimestamp(timeStamp)
            setStatus(UpdateStatus.VERIFIED)
            setPersistentStatus(UpdateStatus.Persistent.VERIFIED)
            setVersion("$name ($buildDate)")
        }
    }

    @Throws(Exception::class)
    private fun verifyPackage(file: File) {
        try {
            android.os.RecoverySystem.verifyPackage(file, null, null)
        } catch (e: Exception) {
            if (file.exists()) {
                file.delete()
                throw Exception("Verification failed, file has been deleted")
            } else {
                throw e
            }
        }
    }

    private fun addUpdate(update: Update) {
        val controller = UpdaterController.getInstance(activity)
        controller.addUpdate(update, false)
    }

    private fun getTimeStamp(file: File): Long {
        return try {
            val metadataContent = readZippedFile(file, METADATA_PATH)
            val lines = metadataContent.split("\n")
            for (line in lines) {
                if (!line.startsWith(METADATA_TIMESTAMP_KEY)) continue

                val timeStampStr = line.replace(METADATA_TIMESTAMP_KEY, "")
                return timeStampStr.toLong()
            }
            System.currentTimeMillis()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read date from local update zip package", e)
            System.currentTimeMillis()
        } catch (e: NumberFormatException) {
            Log.e(TAG, "Failed to parse timestamp number from zip metadata file", e)
            System.currentTimeMillis()
        }
    }

    @Throws(IOException::class)
    private fun readZippedFile(file: File, path: String): String {
        val sb = StringBuilder()
        var iStream: InputStream? = null

        try {
            ZipFile(file).use { zip ->
                val iterator = zip.entries()
                while (iterator.hasMoreElements()) {
                    val entry = iterator.nextElement()
                    if (!METADATA_PATH.equals(entry.name)) continue

                    iStream = zip.getInputStream(entry)
                    break
                }

                if (iStream == null) {
                    throw FileNotFoundException("Couldn't find $path in ${file.name}")
                }

                val buffer = ByteArray(1024)
                var read: Int
                while (iStream!!.read(buffer).also { read = it } > 0) {
                    sb.append(String(buffer, 0, read, StandardCharsets.UTF_8))
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read file from zip package", e)
            throw e
        } finally {
            iStream?.close()
        }

        return sb.toString()
    }

    private fun showSnackbar(messageResId: Int) {
        activity.runOnUiThread {
            Snackbar.make(activity.requireViewById(android.R.id.content), messageResId, Snackbar.LENGTH_LONG).show()
        }
    }

    interface Callbacks {
        fun onImportStarted()
        fun onImportCompleted(update: Update?)
    }

    companion object {
        private const val REQUEST_PICK = 9061
        private const val TAG = "UpdateImporter"
        private const val MIME_ZIP = "application/zip"
        private const val FILE_NAME = "localUpdate.zip"
        private const val METADATA_PATH = "META-INF/com/android/metadata"
        private const val METADATA_TIMESTAMP_KEY = "post-timestamp="
    }
}
