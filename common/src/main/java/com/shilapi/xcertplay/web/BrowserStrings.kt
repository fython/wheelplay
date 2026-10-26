package com.shilapi.xcertplay.web

import android.content.Context
import android.text.TextUtils
import com.shilapi.xcertplay.shared.R as UiR
import org.json.JSONObject

/** Browser copy uses the same Android-selected resources as the native app. */
internal object BrowserStrings {
    private val resources =
        mapOf(
            "web_location_title" to UiR.string.web_location_title,
            "web_location_help" to UiR.string.web_location_help,
            "web_location_https" to UiR.string.web_location_https,
            "web_location_unsupported" to UiR.string.web_location_unsupported,
            "web_location_checking" to UiR.string.web_location_checking,
            "web_location_denied" to UiR.string.web_location_denied,
            "web_location_timeout" to UiR.string.web_location_timeout,
            "web_location_unavailable" to UiR.string.web_location_unavailable,
            "web_location_ready" to UiR.string.web_location_ready,
            "web_location_active" to UiR.string.web_location_active,
            "web_video_recovering" to UiR.string.web_video_recovering,
            "web_video_input_unavailable" to UiR.string.web_video_input_unavailable,
            "web_ignore_browser_zoom" to UiR.string.web_ignore_browser_zoom,
            "web_ignore_browser_zoom_help" to UiR.string.web_ignore_browser_zoom_help,
            "web_browser_paired" to UiR.string.web_browser_paired,
            "web_start_display" to UiR.string.web_start_display,
            "web_paired_ready_to_start" to UiR.string.web_paired_ready_to_start,
            "web_pair_start_display" to UiR.string.web_pair_start_display,
            "web_paired_choose_the_options_above_then_tap_start_display" to
                UiR.string.web_paired_choose_the_options_above_then_tap_start_display,
            "web_paired_for_this_session_but_the_device_could_not" to
                UiR.string.web_paired_for_this_session_but_the_device_could_not,
            "web_paired_for_this_session_this_browser_cannot_remember_the" to
                UiR.string.web_paired_for_this_session_this_browser_cannot_remember_the,
            "web_restoring_pairing" to UiR.string.web_restoring_pairing,
            "web_pairing_restored_automatically_choose_the_options_above_then_tap" to
                UiR.string.web_pairing_restored_automatically_choose_the_options_above_then_tap,
            "web_remembered_device_expired_or_removed_pair_again" to
                UiR.string.web_remembered_device_expired_or_removed_pair_again,
            "web_remembered_browser" to UiR.string.web_remembered_browser,
            "web_waiting_to_restore_pairing" to UiR.string.web_waiting_to_restore_pairing,
            "web_could_not_restore_pairing_yet_check_the_service_and" to
                UiR.string.web_could_not_restore_pairing_yet_check_the_service_and,
            "web_the_pairing_code_in_this_link_has_expired_scan" to
                UiR.string.web_the_pairing_code_in_this_link_has_expired_scan,
            "web_waiting_to_pair" to UiR.string.web_waiting_to_pair,
            "web_generating_qr_code" to UiR.string.web_generating_qr_code,
            "web_could_not_load_qr_code_refresh_and_try_again" to
                UiR.string.web_could_not_load_qr_code_refresh_and_try_again,
            "web_scan_with_the_server_app_and_allow_the_connection" to
                UiR.string.web_scan_with_the_server_app_and_allow_the_connection,
            "web_qr_code_expired_refresh_and_scan_again" to
                UiR.string.web_qr_code_expired_refresh_and_scan_again,
            "web_could_not_check_pairing_status_refresh_the_qr_code" to
                UiR.string.web_could_not_check_pairing_status_refresh_the_qr_code,
            "web_qr_code_unavailable_refresh_and_try_again_or_enter" to
                UiR.string.web_qr_code_unavailable_refresh_and_try_again_or_enter,
            "web_https_is_disabled_enable_it_in_the_app_s" to
                UiR.string.web_https_is_disabled_enable_it_in_the_app_s,
            "web_preparing_https_close_and_reopen_this_section_shortly" to
                UiR.string.web_preparing_https_close_and_reopen_this_section_shortly,
            "web_certificate_information_is_temporarily_unavailable" to
                UiR.string.web_certificate_information_is_temporarily_unavailable,
            "web_disconnected" to UiR.string.web_disconnected,
            "web_reconnecting" to UiR.string.web_reconnecting,
            "web_connecting" to UiR.string.web_connecting,
            "web_keep_wheelplay_in_the_foreground_on_android_tap_connect" to
                UiR.string.web_keep_wheelplay_in_the_foreground_on_android_tap_connect,
            "web_connected_waiting_for_video" to UiR.string.web_connected_waiting_for_video,
            "web_waiting_for_iphone_video" to UiR.string.web_waiting_for_iphone_video,
            "web_fallback" to UiR.string.web_fallback,
            "web_transport_touch_connected" to UiR.string.web_transport_touch_connected,
            "web_waiting_for_recovery" to UiR.string.web_waiting_for_recovery,
            "web_connected_waiting_for_iphone_video" to
                UiR.string.web_connected_waiting_for_iphone_video,
            "web_waiting_for_iphone_touch_input" to UiR.string.web_waiting_for_iphone_touch_input,
            "web_jpeg_touch_connected" to UiR.string.web_jpeg_touch_connected,
            "web_could_not_connect" to UiR.string.web_could_not_connect,
            "web_check_that_the_server_is_running_and_the_pairing" to
                UiR.string.web_check_that_the_server_is_running_and_the_pairing,
            "web_connection_interrupted_retrying" to UiR.string.web_connection_interrupted_retrying,
            "web_check_that_the_server_and_head_unit_are_still" to
                UiR.string.web_check_that_the_server_and_head_unit_are_still,
            "web_could_not_pair" to UiR.string.web_could_not_pair,
            "web_check_the_service_and_network_or_scan_again_enter" to
                UiR.string.web_check_the_service_and_network_or_scan_again_enter,
            "web_browser_connection_interrupted_pair_again_and_retry" to
                UiR.string.web_browser_connection_interrupted_pair_again_and_retry,
            "web_requesting_iphone_connection" to UiR.string.web_requesting_iphone_connection,
            "web_this_browser_does_not_support_full_screen" to
                UiR.string.web_this_browser_does_not_support_full_screen,
            "web_use_the_browser_menu_to_toggle_full_screen" to
                UiR.string.web_use_the_browser_menu_to_toggle_full_screen,
            "web_exit_full_screen" to UiR.string.web_exit_full_screen,
            "web_full_screen" to UiR.string.web_full_screen,
            "web_desktop_back" to UiR.string.web_desktop_back,
            "web_unsupported_microphone_sample_format" to
                UiR.string.web_unsupported_microphone_sample_format,
            "web_tap_the_display_to_allow_audio_playback" to
                UiR.string.web_tap_the_display_to_allow_audio_playback,
            "web_browser_microphones_require_trusted_https_open_the_https_address" to
                UiR.string.web_browser_microphones_require_trusted_https_open_the_https_address,
            "web_this_browser_does_not_support_microphone_audio_processing_use" to
                UiR.string.web_this_browser_does_not_support_microphone_audio_processing_use,
            "web_microphone_stopped_audio_input_has_returned_to_android" to
                UiR.string.web_microphone_stopped_audio_input_has_returned_to_android,
            "web_microphone_permission_denied_allow_this_site_to_use_the" to
                UiR.string.web_microphone_permission_denied_allow_this_site_to_use_the,
            "web_if_the_browser_blocks_autoplay_tap_the_display_to" to
                UiR.string.web_if_the_browser_blocks_autoplay_tap_the_display_to,
            "web_microphone_sample_format_unavailable_input_has_returned_to_android" to
                UiR.string.web_microphone_sample_format_unavailable_input_has_returned_to_android,
            "web_wheelplay_head_unit_display" to UiR.string.web_wheelplay_head_unit_display,
            "ui_waiting_for_connection" to UiR.string.ui_waiting_for_connection,
            "web_disconnect" to UiR.string.web_disconnect,
            "web_local_network_head_unit_display" to UiR.string.web_local_network_head_unit_display,
            "web_connect_your_carplay_display" to UiR.string.web_connect_your_carplay_display,
            "web_for_the_first_connection_tap_scan_qr_code_to" to
                UiR.string.web_for_the_first_connection_tap_scan_qr_code_to,
            "web_then_scan_the_qr_code_below_and_allow_the" to
                UiR.string.web_then_scan_the_qr_code_below_and_allow_the,
            "web_hide_top_bar_after_connecting" to UiR.string.web_hide_top_bar_after_connecting,
            "web_let_carplay_fill_the_page_refresh_to_return_to" to
                UiR.string.web_let_carplay_fill_the_page_refresh_to_return_to,
            "web_hide_top_bar_enter_full_screen_on_start" to
                UiR.string.web_hide_top_bar_enter_full_screen_on_start,
            "web_enter_full_screen_when_starting_and_hide_the_top" to
                UiR.string.web_enter_full_screen_when_starting_and_hide_the_top,
            "web_display_video_with_canvas_experimental" to
                UiR.string.web_display_video_with_canvas_experimental,
            "web_decode_video_offscreen_then_draw_it_on_a_canvas" to
                UiR.string.web_decode_video_offscreen_then_draw_it_on_a_canvas,
            "web_video_renderer_title" to UiR.string.web_video_renderer_title,
            "web_video_renderer_native" to UiR.string.web_video_renderer_native,
            "web_video_renderer_native_description" to UiR.string.web_video_renderer_native_description,
            "web_video_renderer_compat" to UiR.string.web_video_renderer_compat,
            "web_video_renderer_compat_description" to UiR.string.web_video_renderer_compat_description,
            "web_video_renderer_direct" to UiR.string.web_video_renderer_direct,
            "web_video_renderer_direct_description" to UiR.string.web_video_renderer_direct_description,
            "web_video_renderer_experimental" to UiR.string.web_video_renderer_experimental,
            "web_video_renderer_hint" to UiR.string.web_video_renderer_hint,
            "web_video_renderer_unavailable" to UiR.string.web_video_renderer_unavailable,
            "web_video_renderer_failed" to UiR.string.web_video_renderer_failed,
            "web_browser_microphone_requires_https" to
                UiR.string.web_browser_microphone_requires_https,
            "web_local_http_pages_cannot_request_microphone_permission_in_the" to
                UiR.string.web_local_http_pages_cannot_request_microphone_permission_in_the,
            "web_loading" to UiR.string.web_loading,
            "web_download_local_certificate" to UiR.string.web_download_local_certificate,
            "web_open_https_page" to UiR.string.web_open_https_page,
            "web_pairing_restored_choose_the_options_above_then_tap_start" to
                UiR.string.web_pairing_restored_choose_the_options_above_then_tap_start,
            "web_head_unit_pairing_qr_code" to UiR.string.web_head_unit_pairing_qr_code,
            "web_scan_to_connect_the_head_unit" to UiR.string.web_scan_to_connect_the_head_unit,
            "web_refresh_qr_code" to UiR.string.web_refresh_qr_code,
            "web_you_can_also_enter_the_pairing_code" to
                UiR.string.web_you_can_also_enter_the_pairing_code,
            "web_six_digit_pairing_code_on_the_device" to
                UiR.string.web_six_digit_pairing_code_on_the_device,
            "web_enter_pairing_code" to UiR.string.web_enter_pairing_code,
            "web_connect_the_device_and_head_unit_to_the_same" to
                UiR.string.web_connect_the_device_and_head_unit_to_the_same,
            "web_audio_and_microphone_use_android_by_default_enable_browser" to
                UiR.string.web_audio_and_microphone_use_android_by_default_enable_browser,
            "web_carplay_display" to UiR.string.web_carplay_display,
            "web_live_carplay_display" to UiR.string.web_live_carplay_display,
            "web_live_carplay_video" to UiR.string.web_live_carplay_video,
            "web_live_carplay_video_canvas" to UiR.string.web_live_carplay_video_canvas,
            "web_head_unit_connected" to UiR.string.web_head_unit_connected,
            "ui_connect_iphone" to UiR.string.ui_connect_iphone,
            "web_uses_android_s_current_usb_wireless_connection_settings_for" to
                UiR.string.web_uses_android_s_current_usb_wireless_connection_settings_for,
            "web_enable_javascript_in_your_browser_settings_then_reload" to
                UiR.string.web_enable_javascript_in_your_browser_settings_then_reload,
        )

    fun messages(context: Context): JSONObject =
        JSONObject().apply { resources.forEach { (key, id) -> put(key, context.getString(id)) } }

    fun script(context: Context): String =
        "globalThis.wheelplayMessages = " +
            messages(context).toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") +
            ";"

    fun render(context: Context, template: String): String {
        val locale = context.resources.configuration.locales[0].toLanguageTag()
        // Android uses ICU, which rejects unescaped closing braces even though the JVM accepts them.
        return Regex("\\{\\{([a-z0-9_]+)\\}\\}").replace(template) { match ->
            val key = match.groupValues[1]
            TextUtils.htmlEncode(
                if (key == "locale") locale else context.getString(resources.getValue(key))
            )
        }
    }
}
