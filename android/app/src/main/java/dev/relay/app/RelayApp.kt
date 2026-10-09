package dev.relay.app

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class RelayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android ships a stripped-down "BC"; sshj needs the full one.
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        AppLog.init(this)
        Relay.init(this)
    }
}
