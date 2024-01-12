package org.sttdemo

import ai.coqui.libstt.STTModel
import ai.coqui.libstt.STTStreamingState
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.*
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.android.synthetic.main.activity_main.*
import ai.kitt.snowboy.SnowboyDetect
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean


class MainActivity : AppCompatActivity() {
    //sys load library

    companion object {
        init {
            try {
                System.loadLibrary("snowboy-detect-android")
                Log.i("JNI", "Library loaded successfully.")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("JNIError", "Error loading library: ${e.message}")
            }
        }
    }


    private var tts: TextToSpeech? = null

    private var model: STTModel? = null

    private var snowboy: SnowboyDetect? = null

    private var activationThread: Thread? = null
    private var transcriptionThread: Thread? = null
    private var isRecording: AtomicBoolean = AtomicBoolean(false)

    private val TFLITE_MODEL_FILENAME = "model.tflite"
    private val SCORER_FILENAME = "kenlm.scorer"

    private var modelsPath = ""

    private var ACTIVE_UMDL = "hotword.umdl"
    private var ACTIVE_RES = "common.res"

    private val SAMPLE_RATE = 16000;
    private val LISTEN_AFTER_SILENCE_TIME = 1080;
    private val player = MediaPlayer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupTTS()
        checkPermission()
        setupModelsPath()

    }

    private fun perceive() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);

        // at 16000Hz, this corresponds to 2048/16000 = 0.128s or 128ms.
        // Buffer size in bytes: for 0.1 second of audio
        //val audioBufferSize = (SAMPLE_RATE * 0.1).toInt()

        val audioBufferSize = 2048
        val audioData = ShortArray(audioBufferSize)
        var transcribing = true
        var streamContext: STTStreamingState? = null
        var lastSpeakTime: Long = System.currentTimeMillis()

        try {
            streamContext = model?.createStream()
            Log.i("STT", "Stream is Ready!")
        } catch (e: Error) {
            streamContext = null
            Log.e("STT", "Error setting STT: ${e.message}")
        }

        runOnUiThread {
            btnStartInference.text = "FERMA REGISTRAZIONE"
            btnStartInference.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#F44336")) //bottone rosso
        }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            audioBufferSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("VADThreadERROR", "Audio Record can't initialize!");
            return;
        }

        recorder.startRecording();

        snowboy?.Reset()

        while (isRecording.get()) {

            recorder.read(audioData, 0, audioBufferSize)

            // Snowboy model hotword detection.
            val result = snowboy!!.RunDetection(audioData, audioData.size)

            if (result > 0) {
                Log.i("Snowboy: ", "Hotword detected!")

                runOnUiThread {
                    transcription.text = ""
                    btnStartInference.text = "FERMA REGISTRAZIONE"
                    btnStartInference.backgroundTintList =
                        ColorStateList.valueOf(Color.parseColor("#F44336")) //bottone rosso
                }

                lastSpeakTime = System.currentTimeMillis()

                transcribing = true
                player.start()

            } else if (result == -2) {
                //VAD not speaking
                val silenceTime = System.currentTimeMillis() - lastSpeakTime

                if (transcribing) {
                    Log.i("VAD", "silencetime: $silenceTime")
                }

                if (transcribing && silenceTime >= LISTEN_AFTER_SILENCE_TIME) {

                    transcribing = false;

                    val decoded = model?.finishStream(streamContext)

                    runOnUiThread {
                        transcription.text = decoded
                        btnStartInference.text = "REGISTRA"
                        transcription.text = decoded
                        btnStartInference.backgroundTintList =
                            ColorStateList.valueOf(Color.parseColor("#8BC34A")) //bottone verde
                    }


                    Log.i("STT", "STT Stopped")

                    try {
                        streamContext = model?.createStream()
                        Log.i("STT", "Stream is Ready!")
                    } catch (e: Error) {
                        streamContext = null
                        Log.e("STT", "Error setting STT: ${e.message}")
                    }

                }

                //Log.w("Snowboy", "High CPU Usage -2");

            } else if (result == -1) {
                Log.e("Snowboy", "Unknown Detection Error");
            } else if (result == 0) {
                //VAD speaking
                //Update last speak time
                lastSpeakTime = System.currentTimeMillis()

                // post a higher CPU usage:
                //Log.w("Snowboy", "High CPU Usage");
            }

            if (transcribing && streamContext != null) {
                Log.i("STT", "Transcribing..")
                model?.let { model ->
                    model.feedAudioContent(streamContext, audioData, audioData.size)
                    val decoded = model.intermediateDecode(streamContext)
                    runOnUiThread { transcription.text = decoded }
                }

            }

        }



        recorder.stop()
        recorder.release()

        /*
            transcriptionThread = Thread(Runnable { transcribe() }, "Transcription Thread")
            transcriptionThread?.start()
        */

    }

    private fun transcribe() {
        // We read from the recorder in chunks of 2048 shorts. With a model that expects its input
        // at 16000Hz, this corresponds to 2048/16000 = 0.128s or 128ms.
        val audioBufferSize = 2048
        val audioData = ShortArray(audioBufferSize)

        runOnUiThread {
            btnStartInference.text = "FERMA REGISTRAZIONE"
            btnStartInference.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#F44336")) //bottone rosso
        }

        model?.let { model ->
            val streamContext = model.createStream()

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                model.sampleRate(),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                audioBufferSize
            )
            recorder.startRecording()

            while (isRecording.get()) {
                recorder.read(audioData, 0, audioBufferSize)
                model.feedAudioContent(streamContext, audioData, audioData.size)
                val decoded = model.intermediateDecode(streamContext)
                runOnUiThread { transcription.text = decoded }
            }

            val decoded = model.finishStream(streamContext)

            runOnUiThread {
                btnStartInference.text = "REGISTRA"
                transcription.text = decoded
                btnStartInference.backgroundTintList =
                    ColorStateList.valueOf(Color.parseColor("#8BC34A")) //bottone verde
            }

            recorder.stop()
            recorder.release()
        }
    }

    private fun snowboySetup(): Boolean {

        ACTIVE_UMDL = "$modelsPath/$ACTIVE_UMDL"
        ACTIVE_RES = "$modelsPath/$ACTIVE_RES"

        for (path in listOf(ACTIVE_UMDL, ACTIVE_RES)) {
            if (!File(path).exists()) {
                status.append("Snowboy creation failed: $path does not exist.\n")
                return false
            }
        }

        this.snowboy = SnowboyDetect(ACTIVE_RES, ACTIVE_UMDL) //detector setupped

        player.setDataSource("$modelsPath/ding.wav")
        player.prepare() //prepara il player a fare ding in caso di attivazione

        try {
            //snowboy params
            //snowboy?.SetSensitivity("0.6");
            snowboy?.SetAudioGain(1F);
            snowboy?.ApplyFrontend(true);

            Log.i("Snowboy", "Snowboy setted successfully.")
        } catch (e: Error) {
            Log.e("Snowboy", "Error setting snowboy: ${e.message}")
        }

        return true
    }

    private fun createModel(): Boolean {

        val tfliteModelPath = "$modelsPath/$TFLITE_MODEL_FILENAME"
        val scorerPath = "$modelsPath/$SCORER_FILENAME"

        for (path in listOf(tfliteModelPath, scorerPath)) {
            if (!File(path).exists()) {
                status.append("Model creation failed: $path does not exist.\n")
                return false
            }
        }

        model = STTModel(tfliteModelPath)
        model?.enableExternalScorer(scorerPath)

        return true
    }

    private fun setupModelsPath() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && this.modelsPath.isEmpty()) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            i.addCategory(Intent.CATEGORY_DEFAULT)
            startActivityForResult(Intent.createChooser(i, "Scegli cartella con modelli"), 129)
        }

    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 129 && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->

                val docId = uri.path.toString()
                //Log.e("STT Debug", "Si è verificato un errore: $docId")
                val split = docId.split(":").toTypedArray()
                val type = split[0]
                if (type.contains("primary", true)) {
                    this.modelsPath =
                        Environment.getExternalStorageDirectory().toString() + "/" + split[1]
                    status.text = "Ready. $modelsPath model setted!.\n"
                }

            }
        }
    }

    private fun startListening() {
        if (isRecording.compareAndSet(false, true)) {

            activationThread = Thread(Runnable { perceive() }, "Perceiving Thread")
            activationThread?.start()
            /*
            transcriptionThread = Thread(Runnable { transcribe() }, "Transcription Thread")
            transcriptionThread?.start()
            */
        }
    }

    private fun stopListening() {

        isRecording.set(false)
        speakTTS(transcription.text as String)

    }

    private fun setupTTS() {
        this.tts = TextToSpeech(
            applicationContext,
            { status ->
                if (status == TextToSpeech.SUCCESS) {
                    Log.println(Log.INFO, "TTS", "initialized TTS")
                } else if (status == TextToSpeech.ERROR) {
                    Log.println(Log.ERROR, "TTS", "ERROR initialize TTS")
                } else {
                    // missing data, install it

                    val installIntent = Intent()
                    installIntent.action = TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA
                    startActivity(installIntent)

                }
            }, "com.google.android.tts"
        )


    }

    private fun speakTTS(testo: String) {
        val streamToUse = AudioManager.STREAM_MUSIC

        val speechPitch = 1.0f
        val utteranceId = "utterance_id"
        val params = Bundle()
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, streamToUse)
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        if (this.tts != null) {
            this.tts!!.speak(testo, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }

    }

    private fun checkPermission() {
        val permissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.RECORD_AUDIO
        )

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest, 3)
        }

    }

    fun onRecordClick(v: View?) {

        if (this.modelsPath.isEmpty()) {
            setupModelsPath()
        }

        if (snowboy == null) {
            if (!snowboySetup()) {
                return
            }
            status.append("Created snowboy detector.\n")
        }

        if (model == null) {
            if (!createModel()) {
                return
            }
            status.append("Created model.\n")
        }

        if (isRecording.get()) {
            stopListening()
        } else {
            startListening()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (model != null) {
            model?.freeModel()
        }
    }
}
