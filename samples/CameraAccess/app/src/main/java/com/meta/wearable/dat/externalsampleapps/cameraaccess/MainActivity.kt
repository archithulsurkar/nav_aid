/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// CameraAccess Sample App - Main Activity
//
// This is the main entry point for the CameraAccess sample application that demonstrates how to use
// the Meta Wearables Device Access Toolkit (DAT) to:
// - Initialize the DAT SDK
// - Handle device permissions (Bluetooth, Internet)
// - Request camera permissions from wearable devices (Ray-Ban Meta glasses)
// - Stream video and capture photos from connected wearable devices

package com.meta.wearable.dat.externalsampleapps.cameraaccess

import android.Manifest.permission.BLUETOOTH
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.CAMERA
import android.Manifest.permission.INTERNET
import android.os.Bundle
import android.view.KeyEvent // NEW: Required to intercept hardware buttons
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.viewModels
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ui.CameraAccessScaffold
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity : ComponentActivity() {
  companion object {
    // Required Android permissions for the DAT SDK to function properly
    val PERMISSIONS: Array<String> = arrayOf(BLUETOOTH, BLUETOOTH_CONNECT, CAMERA, INTERNET)
  }

  val viewModel: WearablesViewModel by viewModels()

  // =====================================================================
  // NEW: Double-click tracking variables
  // =====================================================================
  private var lastVolumePressTime: Long = 0L
  private val DOUBLE_CLICK_THRESHOLD = 500L // 500 milliseconds

  private val permissionCheckLauncher =
    registerForActivityResult(RequestMultiplePermissions()) { permissionsResult ->
      viewModel.onPermissionsResult(permissionsResult) {
        // Initialize the DAT SDK once the permissions are granted
        // This is REQUIRED before using any Wearables APIs
        Wearables.initialize(this)
      }
    }

  private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
  private val permissionMutex = Mutex()
  // Requesting wearable device permissions via the Meta AI app
  private val permissionsResultLauncher =
    registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
      val permissionStatus = result.getOrDefault(PermissionStatus.Denied)
      permissionContinuation?.resume(permissionStatus)
      permissionContinuation = null
    }

  // Convenience method to make a permission request in a sequential manner
  // Uses a Mutex to ensure requests are processed one at a time, preventing race conditions
  suspend fun requestWearablesPermission(permission: Permission): PermissionStatus {
    return permissionMutex.withLock {
      suspendCancellableCoroutine { continuation ->
        permissionContinuation = continuation
        continuation.invokeOnCancellation { permissionContinuation = null }
        permissionsResultLauncher.launch(permission)
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      CameraAccessScaffold(
        viewModel = viewModel,
        onRequestWearablesPermission = ::requestWearablesPermission,
      )
    }
  }

  override fun onStart() {
    super.onStart()
    // First, ensure the app has necessary Android permissions
    permissionCheckLauncher.launch(PERMISSIONS)
  }

  // =====================================================================
  // NEW: Hardware Button Interceptor for Double-Click
  // =====================================================================
  override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
    // Listen for either Volume Down or Volume Up
    if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
      val currentTime = System.currentTimeMillis()

      if (currentTime - lastVolumePressTime < DOUBLE_CLICK_THRESHOLD) {
        // Double click detected! Trigger the OCR scan.
        viewModel.triggerOcrFromHardwareButton()

        // Reset the timer to prevent a rapid triple-click from triggering it twice
        lastVolumePressTime = 0L

        // Return true to consume this second press so it doesn't adjust the volume again
        return true
      } else {
        // First click. Record the time.
        lastVolumePressTime = currentTime

        // Allow the first press to pass through and change the volume normally
        return super.onKeyDown(keyCode, event)
      }
    }

    // Let all other keys behave normally
    return super.onKeyDown(keyCode, event)
  }
}