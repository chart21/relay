package dev.relay.app.shots

import org.robolectric.annotation.Config

@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h891dp-night-xxhdpi")
class ShotsDark : ShotsBase("dark")
