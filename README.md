# Android Microphone Streaming

Android demo application that streams audio from the microphone to STT and transcribes it.

## Prerequisites

#### Download model

Download a pre-trained model and scorer from the [Coqui Model Zoo](https://coqui.ai/models).

Move the model files (`.tflite`, `.scorer`), to the demo application's data directory on your android device.
Mind that the data directory will only be present after installing and launching the app once.

```
adb push model.tflite huge-vocab.scorer /storage/emulated/0/Android/data/org.sttdemo/files/
```

You can also copy the files from your file browser to the device.

#### Android device with USB Debugging

Connect an android device and make sure to enable USB-Debugging in the developer settings of the device. If haven't already, you can activate your developer settings by following [this guide from android](https://developer.android.com/studio/debug/dev-options#enable).

## Installation

To install the example app on your connected android device you can either use the command line or Android Studio.

### Command Line

```
cd android_mic_streaming
./gradlew installDebug
``` 

### Android Studio

Open the `android_mic_streaming` directory in Android Studio.  
Run the app and your connected android device.

## Usage

Start recording by pressing the button and the app will transcribe the spoken text.

## Fine-tuning the Recognition

Based on your use case or the language you are using you might change the values of `BEAM_WIDTH`, `LM_ALPHA` and `LM_BETA` to improve the speech recogintion. 

You can also alter the `NUM_BUFFER_ELEMENTS` to change the size of the audio data buffe trhat is fed into the model. 