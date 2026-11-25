package com.torrenthub.aio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.torrenthub.aio.R
import com.torrenthub.aio.util.AppSecurityHelper

@Composable
fun AppLockScreen(
    vm: AppViewModel,
    onExitApp: () -> Unit
) {
    val p = AppTheme.palette
    val context = LocalContext.current
    val activity = AppSecurityHelper.findActivity(LocalView.current.context)
        ?: AppSecurityHelper.findActivity(context)

    var pinInput by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val biometricSupported = remember(activity) {
        activity != null && AppSecurityHelper.isBiometricAvailable(activity)
    }
    val showBiometric = vm.appLockBiometricEnabled && biometricSupported

    val promptTitle = if (vm.disguiseModeEnabled) {
        stringResource(R.string.disguise_app_title)
    } else {
        stringResource(R.string.applock_prompt_title)
    }
    val promptSubtitle = stringResource(R.string.applock_prompt_subtitle)
    val usePinLabel = stringResource(R.string.applock_btn_use_pin)
    val wrongPinLabel = stringResource(R.string.applock_error_wrong_pin)
    val biometricFailLabel = stringResource(R.string.applock_error_biometric_failed)

    fun triggerBiometric() {
        val act = activity ?: return
        if (!showBiometric) return
        AppSecurityHelper.authenticateBiometric(
            activity = act,
            title = promptTitle,
            subtitle = promptSubtitle,
            negativeButtonText = usePinLabel,
            onSuccess = {
                errorMsg = null
                pinInput = ""
                vm.unlockSession()
            },
            onError = { _ -> },
            onFailed = {
                errorMsg = biometricFailLabel
            }
        )
    }

    LaunchedEffect(showBiometric) {
        if (showBiometric) {
            triggerBiometric()
        }
    }

    BackHandler {
        onExitApp()
    }

    fun appendDigit(digit: Char) {
        if (pinInput.length >= 6) return
        errorMsg = null
        val next = pinInput + digit
        pinInput = next
        if (next.length >= vm.appLockPinLength && vm.verifyPinInput(next)) {
            pinInput = ""
            vm.unlockSession()
        } else if (next.length == 6 && !vm.verifyPinInput(next)) {
            errorMsg = wrongPinLabel
            pinInput = ""
        }
    }

    fun submitCurrentPin() {
        if (vm.verifyPinInput(pinInput)) {
            errorMsg = null
            pinInput = ""
            vm.unlockSession()
        } else {
            errorMsg = wrongPinLabel
            pinInput = ""
        }
    }

    Surface(
        color = p.background,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(p.accentBlue.copy(alpha = 0.15f))
                    .border(1.5.dp, p.accentBlue.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = p.accentBlue,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = if (vm.disguiseModeEnabled) stringResource(R.string.disguise_app_title)
                else stringResource(R.string.applock_screen_title),
                fontWeight = FontWeight.ExtraBold,
                fontSize = 20.sp,
                color = p.textPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.applock_screen_subtitle),
                fontSize = 12.5.sp,
                color = p.textMuted,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            // PIN Dots Indicator
            val expectedDots = vm.appLockPinLength.coerceIn(4, 6)
            val totalDots = maxOf(expectedDots, pinInput.length)
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (i in 0 until totalDots) {
                    val filled = i < pinInput.length
                    Box(
                        modifier = Modifier
                            .size(15.dp)
                            .clip(CircleShape)
                            .background(if (filled) p.accentBlue else Color.Transparent)
                            .border(
                                width = 1.8.dp,
                                color = if (filled) p.accentBlue else p.borderColor,
                                shape = CircleShape
                            )
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = errorMsg ?: " ",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = p.accentRed,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(18.dp))

            // Numeric Keypad
            val rows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9")
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        row.forEach { d ->
                            PinKeypadButton(
                                label = d,
                                onClick = { appendDigit(d[0]) }
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (showBiometric) {
                        PinKeypadIconButton(
                            icon = Icons.Default.Fingerprint,
                            tint = p.accentGreen,
                            contentDescription = stringResource(R.string.applock_btn_biometric),
                            onClick = { triggerBiometric() }
                        )
                    } else {
                        Spacer(Modifier.size(66.dp))
                    }

                    PinKeypadButton(
                        label = "0",
                        onClick = { appendDigit('0') }
                    )

                    PinKeypadIconButton(
                        icon = Icons.AutoMirrored.Filled.Backspace,
                        tint = p.textPrimary,
                        contentDescription = stringResource(R.string.cd_delete),
                        onClick = {
                            if (pinInput.isNotEmpty()) {
                                pinInput = pinInput.dropLast(1)
                                errorMsg = null
                            }
                        }
                    )
                }
            }

            if (pinInput.length in 4..6) {
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = { submitCurrentPin() },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.width(200.dp)
                ) {
                    Text(
                        text = stringResource(R.string.applock_btn_unlock),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (showBiometric) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { triggerBiometric() }) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.applock_btn_biometric), fontSize = 12.5.sp)
                }
            }
        }
    }
}

@Composable
private fun PinKeypadButton(
    label: String,
    onClick: () -> Unit
) {
    val p = AppTheme.palette
    Surface(
        color = p.cardSurface,
        shape = CircleShape,
        border = BorderStroke(1.dp, p.borderColor),
        modifier = Modifier
            .size(66.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = p.textPrimary
            )
        }
    }
}

@Composable
private fun PinKeypadIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    contentDescription: String?,
    onClick: () -> Unit
) {
    val p = AppTheme.palette
    Surface(
        color = p.subCardSurface,
        shape = CircleShape,
        border = BorderStroke(1.dp, p.borderColor),
        modifier = Modifier
            .size(66.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

enum class PinDialogMode {
    SETUP_NEW,
    CHANGE_EXISTING,
    VERIFY_TO_DISABLE
}

@Composable
fun PinSetupOrVerifyDialog(
    vm: AppViewModel,
    mode: PinDialogMode,
    onDismiss: () -> Unit,
    onCompleted: () -> Unit
) {
    val p = AppTheme.palette
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    val errCurrentWrong = stringResource(R.string.applock_error_wrong_pin)
    val errFormat = stringResource(R.string.applock_error_pin_format)
    val errMismatch = stringResource(R.string.applock_error_pin_mismatch)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = p.cardSurface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, p.borderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = when (mode) {
                        PinDialogMode.SETUP_NEW -> stringResource(R.string.applock_dialog_setup_title)
                        PinDialogMode.CHANGE_EXISTING -> stringResource(R.string.applock_dialog_change_title)
                        PinDialogMode.VERIFY_TO_DISABLE -> stringResource(R.string.applock_dialog_disable_title)
                    },
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp,
                    color = p.textPrimary
                )

                Text(
                    text = when (mode) {
                        PinDialogMode.VERIFY_TO_DISABLE -> stringResource(R.string.applock_dialog_disable_desc)
                        else -> stringResource(R.string.applock_dialog_setup_desc)
                    },
                    fontSize = 12.sp,
                    color = p.textMuted
                )

                if (mode == PinDialogMode.CHANGE_EXISTING || mode == PinDialogMode.VERIFY_TO_DISABLE) {
                    OutlinedTextField(
                        value = currentPin,
                        onValueChange = { input ->
                            if (input.length <= 6 && input.all { it.isDigit() }) {
                                currentPin = input
                                errorText = null
                            }
                        },
                        singleLine = true,
                        label = { Text(stringResource(R.string.applock_field_current_pin)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (mode == PinDialogMode.SETUP_NEW || mode == PinDialogMode.CHANGE_EXISTING) {
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { input ->
                            if (input.length <= 6 && input.all { it.isDigit() }) {
                                newPin = input
                                errorText = null
                            }
                        },
                        singleLine = true,
                        label = { Text(stringResource(R.string.applock_field_new_pin)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { input ->
                            if (input.length <= 6 && input.all { it.isDigit() }) {
                                confirmPin = input
                                errorText = null
                            }
                        },
                        singleLine = true,
                        label = { Text(stringResource(R.string.applock_field_confirm_pin)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (!errorText.isNullOrBlank()) {
                    Text(
                        text = errorText.orEmpty(),
                        fontSize = 12.sp,
                        color = p.accentRed,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    Button(
                        onClick = {
                            when (mode) {
                                PinDialogMode.VERIFY_TO_DISABLE -> {
                                    if (!vm.verifyPinInput(currentPin)) {
                                        errorText = errCurrentWrong
                                    } else {
                                        vm.disableAppLockConfirmed()
                                        onCompleted()
                                    }
                                }
                                PinDialogMode.CHANGE_EXISTING -> {
                                    if (!vm.verifyPinInput(currentPin)) {
                                        errorText = errCurrentWrong
                                    } else if (!AppSecurityHelper.isValidPinFormat(newPin)) {
                                        errorText = errFormat
                                    } else if (newPin != confirmPin) {
                                        errorText = errMismatch
                                    } else {
                                        vm.configureNewAppLockPin(newPin)
                                        onCompleted()
                                    }
                                }
                                PinDialogMode.SETUP_NEW -> {
                                    if (!AppSecurityHelper.isValidPinFormat(newPin)) {
                                        errorText = errFormat
                                    } else if (newPin != confirmPin) {
                                        errorText = errMismatch
                                    } else {
                                        vm.configureNewAppLockPin(newPin)
                                        onCompleted()
                                    }
                                }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.common_save), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
