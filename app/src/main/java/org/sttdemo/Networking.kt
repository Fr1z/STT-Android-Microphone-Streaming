package org.sttdemo
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class UnsafeTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()

    companion object {
        fun createUnsafeSSLContext(): SSLContext {
            val trustManagers: Array<TrustManager> = arrayOf(UnsafeTrustManager())
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustManagers, SecureRandom())
            return sslContext
        }
    }
}

class Networking(private val serverAddress: String, private val port: Int) {
    private val _data = MutableLiveData<String>()
    val data: LiveData<String> get() = _data

    fun startStreaming(jsonData: String) {
        Log.i("Networking", "Started Network Thread for http://$serverAddress:$port")
        Thread {
            try {

                val sslContext = UnsafeTrustManager.createUnsafeSSLContext()
                HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
                HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }

                val url = URL("https://$serverAddress:$port/api/chat")
                val connection = url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8")

                connection.doOutput = true
                val outputStream = DataOutputStream(connection.outputStream)
                outputStream.write(jsonData.toByteArray(StandardCharsets.UTF_8))
                outputStream.flush()
                outputStream.close()
                Log.i("Networking", "Data should be send")
                val responseCode = connection.responseCode

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Log.e("DataReceiver", "Connessione al server non riuscita. Codice di risposta: $responseCode")
                }

                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                var line: String?

                // Legge ogni riga dello stream e aggiorna la variabile LiveData
                while (reader.readLine().also { line = it } != null) {
                    val receivedData = line ?: continue

                    // Aggiornamento della variabile LiveData sul thread principale
                    Handler(Looper.getMainLooper()).post {
                        try {
                            // get JSONObject from JSON response
                            val respObj = JSONObject(receivedData)
                            // get message string
                            val messageResponse: String = respObj.getString("message")
                            _data.value = messageResponse
                        } catch (e : Exception){
                            Log.e("NETWORK", "noJson: $receivedData")
                        }
                    }
                }

                reader.close()
                connection.disconnect()
            } catch (e: Exception) {
                // Gestire le eccezioni in modo appropriato
                Log.e("Network", "Errore durante la connessione al server", e)
                e.printStackTrace()
            }
        }.start()
    }
}
