package org.localwake.app.ui

import android.content.Context
import com.phoneuse.R
import com.phoneuse.i18n.tr

internal fun resultLabel(context: Context, code: String): String {
    val unified = when (code) {
        "AGENT_FINISHED" -> tr("AI task finished", "AI 작업 완료")
        "AGENT_COMPLETED" -> tr("Agent finished", "에이전트 실행 종료")
        "AGENT_STOPPED" -> tr("Stopped", "중지됨")
        "AGENT_SERVICE_DISCONNECTED" -> tr("Accessibility service disconnected", "접근성 서비스 연결 끊김")
        "AGENT_FAILED" -> tr("Execution failed", "실행 실패")
        "AGENT_NOT_STARTED", "EXECUTOR_UNAVAILABLE" -> tr("Task did not start", "작업이 시작되지 않았어요")
        "EXECUTION_INTERRUPTED", "USER_CANCELLED", "CANCELLED", "CANCELED", "SESSION_REVOKED" -> tr("Task stopped", "작업이 중지됐어요")
        "RECOVERY_REQUIRED", "MIGRATION_RECOVERY_REQUIRED", "ROUTINE_RECOVERY_REQUIRED", "AUTHORITY_RECOVERY_REQUIRED",
        "MIGRATION_PAUSED", "MIGRATION_REQUIRED" -> tr("Tasks paused for recovery", "복구를 위해 작업이 일시 중지됐어요")
        "WAITING_FOR_MANUAL_UNLOCK", "MANUAL_UNLOCK_REQUIRED", "AUTO_UNLOCK_DISABLED" -> tr("Waiting for manual unlock", "직접 잠금 해제를 기다려요")
        "DEVICE_BUSY", "BUSY" -> tr("Device in use", "기기 사용 중")
        "PLAN_PAUSED" -> tr("Task paused by usage limit", "사용량 한도로 작업이 일시 중지됐어요")
        "NOTIFICATION_PERMISSION_REQUIRED" -> tr("Notification permission needed", "알림 권한이 필요해요")
        "ALREADY_UNLOCKED", "UNLOCKED" -> tr("Phone unlocked", "휴대전화 잠금 해제됨")
        "PAUSED" -> tr("Automatic unlock is paused", "자동 잠금 해제가 일시 중지됐어요")
        else -> null
    }
    if (unified != null) return unified
    val resource = when (code) {
    "TASK_MISSING" -> R.string.result_task_missing
    "SUCCESS" -> R.string.result_success
    "LOCK_SCREEN_DETECTED" -> R.string.compat_inspection_success
    "LOCK_SCREEN_UNAVAILABLE" -> R.string.compat_inspection_unknown
    "WAKE_CONFIRMED" -> R.string.result_wake_confirmed
    "GRID_DYNAMIC_FOUND", "GRID_CALIBRATED_FOUND" -> R.string.result_grid_found
    "ACCESSIBILITY_DISCONNECTED" -> R.string.result_accessibility_disconnected
    "EXACT_ALARM_DENIED" -> R.string.result_exact_alarm_denied
    "CREDENTIAL_NOT_READY" -> R.string.result_credential_not_ready
    "MANUAL_RESET_REQUIRED" -> R.string.result_manual_reset_required
    "TEST_ALREADY_PENDING" -> R.string.result_test_already_pending
    "IN_PROGRESS" -> R.string.result_in_progress
    "AUTHENTICATION_REJECTED" -> R.string.result_authentication_rejected
    "PIN_KEYPAD_UNAVAILABLE" -> R.string.result_pin_keypad_unavailable
    "PASSWORD_FIELD_UNAVAILABLE" -> R.string.result_password_field_unavailable
    "UNLOCK_METHOD_CHANGED" -> R.string.result_unlock_method_changed
    "UNLOCK_SURFACE_CHANGED" -> R.string.result_unlock_surface_changed
    "PIN_ENTRY_NOT_EMPTY" -> R.string.result_pin_entry_not_empty
    "PIN_ENTRY_UNVERIFIED" -> R.string.result_pin_entry_unverified
    "CREDENTIAL_INPUT_REJECTED" -> R.string.result_credential_input_rejected
    "PATTERN_GRID_UNAVAILABLE" -> R.string.result_pattern_grid_unavailable
    "WAKE_BLOCKED" -> R.string.result_wake_blocked
    "APP_LAUNCH_UNOBSERVED" -> R.string.result_app_launch_unobserved
    "EXECUTION_BUSY" -> R.string.result_execution_busy
    "ARMED" -> R.string.result_armed
    "INACTIVE" -> R.string.result_inactive
    "FOREGROUND_CONFIRMED" -> R.string.result_foreground_confirmed
    "APP_NOT_INSTALLED" -> R.string.result_app_not_installed
    "APP_LAUNCH_REJECTED" -> R.string.result_app_launch_rejected
    "SERVICE_DISCONNECTED", "SERVICE_UNAVAILABLE" -> R.string.result_service_disconnected
    "EXECUTION_TIMEOUT", "TIMEOUT" -> R.string.result_execution_timeout
    "EXECUTION_ERROR", "EXECUTION_FAILED", "ALARM_DISPATCH_FAILED" -> R.string.result_execution_error
    "TASK_CHANGED" -> R.string.result_task_changed
    "DEVICE_RELOCKED", "KEYGUARD_STILL_LOCKED" -> R.string.result_device_relocked
    "FIRST_MANUAL_UNLOCK_REQUIRED" -> R.string.result_first_manual_unlock_required
    "MISSED_WINDOW" -> R.string.result_missed_window
    "PATTERN_GRID_CHANGED" -> R.string.result_pattern_grid_changed
    else -> when {
        code.startsWith("CREDENTIAL_") -> R.string.result_check_credential
        code.startsWith("PATTERN_GESTURE_") -> R.string.result_pattern_gesture
        else -> null
    }
    }
    return resource?.let(context::getString) ?: tr("Result details unavailable", "결과 세부 정보를 확인할 수 없어요")
}
