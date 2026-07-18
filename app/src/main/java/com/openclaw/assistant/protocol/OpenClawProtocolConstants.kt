package com.openclaw.assistant.protocol

enum class OpenClawCapability(val rawValue: String) {
    Canvas("canvas"),
    Camera("camera"),
    Screen("screen"),
    Sms("sms"),
    VoiceWake("voiceWake"),
    Location("location"),
    Notifications("notifications"),
    System("system"),
    Photos("photos"),
    Contacts("contacts"),
    Calendar("calendar"),
    Motion("motion"),
    Wifi("wifi"),
    App("app"),
    Clipboard("clipboard"),
    Bridge("bridge"),
    Phone("phone"),
    Media("media"),
}

enum class OpenClawCanvasCommand(val rawValue: String) {
    Present("canvas.present"),
    Hide("canvas.hide"),
    Navigate("canvas.navigate"),
    Eval("canvas.eval"),
    Snapshot("canvas.snapshot"),
    ;

    companion object {
        const val NamespacePrefix: String = "canvas."
    }
}

enum class OpenClawCanvasA2UICommand(val rawValue: String) {
    Push("canvas.a2ui.push"),
    PushJSONL("canvas.a2ui.pushJSONL"),
    Reset("canvas.a2ui.reset"),
    ;

    companion object {
        const val NamespacePrefix: String = "canvas.a2ui."
    }
}

enum class OpenClawCameraCommand(val rawValue: String) {
    Snap("camera.snap"),
    Clip("camera.clip"),
    List("camera.list"),
    ;

    companion object {
        const val NamespacePrefix: String = "camera."
    }
}

enum class OpenClawScreenCommand(val rawValue: String) {
    Record("screen.record"),
    ;

    companion object {
        const val NamespacePrefix: String = "screen."
    }
}

enum class OpenClawSmsCommand(val rawValue: String) {
    Send("sms.send"),
    ReadLatest("sms.read_latest"),
    ReadUnread("sms.read_unread"),
    ;

    companion object {
        const val NamespacePrefix: String = "sms."
    }
}

enum class OpenClawPhoneCommand(val rawValue: String) {
    Call("phone.call"),
    ;

    companion object {
        const val NamespacePrefix: String = "phone."
    }
}

enum class OpenClawMediaCommand(val rawValue: String) {
    PlaySearch("media.play_search"),
    ;

    companion object {
        const val NamespacePrefix: String = "media."
    }
}

enum class OpenClawDeviceCommand(val rawValue: String) {
    Status("device.status"),
    Info("device.info"),
    Permissions("device.permissions"),
    Health("device.health"),
    ;

    companion object {
        const val NamespacePrefix: String = "device."
    }
}

enum class OpenClawLocationCommand(val rawValue: String) {
    Get("location.get"),
    History("location.history"),
    LastKnown("location.last_known"),
    SetTracking("location.set_tracking"),
    ;

    companion object {
        const val NamespacePrefix: String = "location."
    }
}

enum class OpenClawNotificationsCommand(val rawValue: String) {
    List("notifications.list"),
    ListMessenger("notifications.list_package"),
    Actions("notifications.actions"),
    ;

    companion object {
        const val NamespacePrefix: String = "notifications."
    }
}

enum class OpenClawSystemCommand(val rawValue: String) {
    Notify("system.notify"),
    Volume("system.volume"),
    Brightness("system.brightness"),
    ;

    companion object {
        const val NamespacePrefix: String = "system."
    }
}

enum class OpenClawPhotosCommand(val rawValue: String) {
    Latest("photos.latest"),
    ;

    companion object {
        const val NamespacePrefix: String = "photos."
    }
}

enum class OpenClawContactsCommand(val rawValue: String) {
    Search("contacts.search"),
    Add("contacts.add"),
    Update("contacts.update"),
    Delete("contacts.delete"),
    ;

    companion object {
        const val NamespacePrefix: String = "contacts."
    }
}

enum class OpenClawCalendarCommand(val rawValue: String) {
    Events("calendar.events"),
    Add("calendar.add"),
    Update("calendar.update"),
    Delete("calendar.delete"),
    ;

    companion object {
        const val NamespacePrefix: String = "calendar."
    }
}

enum class OpenClawMotionCommand(val rawValue: String) {
    Activity("motion.activity"),
    Pedometer("motion.pedometer"),
    ;

    companion object {
        const val NamespacePrefix: String = "motion."
    }
}

enum class OpenClawWifiCommand(val rawValue: String) {
    List("wifi.list"),
    Status("wifi.status"),
    Connect("wifi.connect"),
    ;

    companion object {
        const val NamespacePrefix: String = "wifi."
    }
}

enum class OpenClawAppCommand(val rawValue: String) {
    List("app.list"),
    Launch("app.launch"),
    ;

    companion object {
        const val NamespacePrefix: String = "app."
    }
}

enum class OpenClawClipboardCommand(val rawValue: String) {
    Read("clipboard.read"),
    Write("clipboard.write"),
    ;

    companion object {
        const val NamespacePrefix: String = "clipboard."
    }
}

enum class OpenClawVoiceWakeCommand(val rawValue: String) {
    GetMode("voiceWake.get_mode"),
    SetMode("voiceWake.set_mode"),
    Status("voiceWake.status"),
    ;

    companion object {
        const val NamespacePrefix: String = "voiceWake."
    }
}

enum class OpenClawBridgeCommand(val rawValue: String) {
    Status("bridge.status"),
    Manifest("bridge.manifest"),
    Execute("bridge.execute"),
    Grants("bridge.grants"),
    Revoke("bridge.revoke"),
    ;

    companion object {
        const val NamespacePrefix: String = "bridge."
    }
}
