package dev.relay.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Ed25519 SSH identity. The 32-byte seed is stored encrypted by an Android Keystore AES key. */
class Identity private constructor(private val priv: EdDSAPrivateKey, private val pub: EdDSAPublicKey) {

    val publicLine: String
        get() {
            val bos = ByteArrayOutputStream()
            DataOutputStream(bos).run {
                writeBlob("ssh-ed25519".toByteArray()); writeBlob(pub.abyte)
            }
            return "ssh-ed25519 " + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP) + " relay-phone"
        }

    val keyProvider = object : KeyProvider {
        override fun getPrivate(): PrivateKey = priv
        override fun getPublic(): PublicKey = pub
        override fun getType(): KeyType = KeyType.ED25519
    }

    private fun DataOutputStream.writeBlob(b: ByteArray) { writeInt(b.size); write(b) }

    companion object {
        private const val ALIAS = "relay-wrap"
        private val spec = EdDSANamedCurveTable.getByName("Ed25519")

        private fun wrapKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            g.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()
            )
            return g.generateKey()
        }

        fun loadOrCreate(ctx: Context): Identity {
            val f = File(ctx.filesDir, "id_seed.enc")
            val seed: ByteArray
            if (f.exists()) {
                val raw = f.readBytes()
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
                seed = c.doFinal(raw, 12, raw.size - 12)
            } else {
                seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, wrapKey())
                f.writeBytes(c.iv + c.doFinal(seed))
            }
            val ps = EdDSAPrivateKeySpec(seed, spec)
            val id = Identity(EdDSAPrivateKey(ps), EdDSAPublicKey(EdDSAPublicKeySpec(ps.a, spec)))
            // Plain public key file so a provisioning script can read it (run-as on debug builds).
            File(ctx.filesDir, "id_ed25519.pub").writeText(id.publicLine + "\n")
            return id
        }
    }
}
