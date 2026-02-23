#include "ai_engine.hpp"
#include <android/log.h>
#include <unistd.h>

#define TAG "StreamX_AI_Engine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

const size_t MAX_AUDIO_BUFFER_SIZE = 16000 * 60; 

AIEngine::AIEngine() : isRunning(false), recognizer(nullptr), stream(nullptr) {
    audioBuffer.reserve(16000 * 10); 
}

AIEngine::~AIEngine() { 
    stop(); 
}

bool AIEngine::init(const std::string& modelPath) {
    // --- ULTIMATE SAFETY CHECK: File Existence ---
    std::string encoder = modelPath + "/encoder.onnx";
    std::string decoder = modelPath + "/decoder.onnx";
    std::string joiner = modelPath + "/joiner.onnx";
    std::string tokens = modelPath + "/tokens.txt";

    if (access(encoder.c_str(), R_OK) != 0 || access(decoder.c_str(), R_OK) != 0 || 
        access(joiner.c_str(), R_OK) != 0 || access(tokens.c_str(), R_OK) != 0) {
        LOGE("Critical Error: Essential Sherpa Model files are MISSING in: %s", modelPath.c_str());
        return false; // Return false cleanly instead of crashing
    }

    SherpaOnnxOnlineRecognizerConfig config;
    memset(&config, 0, sizeof(config));

    config.model_config.transducer.encoder = encoder.c_str();
    config.model_config.transducer.decoder = decoder.c_str();
    config.model_config.transducer.joiner = joiner.c_str();
    config.model_config.tokens = tokens.c_str();
    config.model_config.num_threads = 2; 
    config.model_config.provider = "cpu";
    
    config.feat_config.sample_rate = 16000;
    config.feat_config.feature_dim = 80;

    recognizer = SherpaOnnxCreateOnlineRecognizer(&config);
    if (!recognizer) {
        LOGE("Fatal: Failed to load Sherpa-ONNX Model internally.");
        return false;
    }
    
    stream = SherpaOnnxCreateOnlineStream(recognizer);
    if (!stream) {
        LOGE("Fatal: Failed to create Sherpa-ONNX Stream.");
        SherpaOnnxDestroyOnlineRecognizer(recognizer);
        recognizer = nullptr;
        return false;
    }
    
    LOGD("Sherpa-ONNX AI Engine Initialized Successfully.");
    isRunning = true;
    
    workerThread = std::thread(&AIEngine::processingLoop, this);
    return true;
}

void AIEngine::pushAudio(const std::vector<float>& pcm32) {
    if (!isRunning) return;
    std::lock_guard<std::mutex> lock(audioMutex);
    
    if (audioBuffer.size() + pcm32.size() > MAX_AUDIO_BUFFER_SIZE) {
        size_t removeCount = pcm32.size();
        if (removeCount > audioBuffer.size()) removeCount = audioBuffer.size();
        audioBuffer.erase(audioBuffer.begin(), audioBuffer.begin() + removeCount);
    }
    audioBuffer.insert(audioBuffer.end(), pcm32.begin(), pcm32.end());
}

void AIEngine::processingLoop() {
    pthread_setname_np(pthread_self(), "StreamX_Sherpa");

    while (isRunning) {
        std::vector<float> processBuffer;
        
        {
            std::lock_guard<std::mutex> lock(audioMutex);
            if (audioBuffer.size() >= 16000 * 0.5) { 
                processBuffer = std::move(audioBuffer); 
                audioBuffer.clear();
            }
        }

        if (!processBuffer.empty() && stream && recognizer) {
            SherpaOnnxOnlineStreamAcceptWaveform(stream, 16000, processBuffer.data(), processBuffer.size());
            
            while (SherpaOnnxIsOnlineStreamReady(recognizer, stream)) {
                SherpaOnnxDecodeOnlineStream(recognizer, stream);
            }
            
            const SherpaOnnxOnlineRecognizerResult* result = SherpaOnnxGetOnlineStreamResult(recognizer, stream);
            if (result && result->text) {
                if (strlen(result->text) > 0) {
                    currentText = result->text;
                }
                SherpaOnnxDestroyOnlineRecognizerResult(result);
            }
        } else {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        }
    }
}

std::string AIEngine::getCurrentSubtitle() {
    return currentText;
}

void AIEngine::stop() {
    if (!isRunning) return;
    LOGD("Stopping Sherpa-ONNX AI Engine...");
    
    isRunning = false; 
    
    if (workerThread.joinable()) {
        workerThread.join();
    }
    
    // Memory release with safety checks
    if (stream) { SherpaOnnxDestroyOnlineStream(stream); stream = nullptr; }
    if (recognizer) { SherpaOnnxDestroyOnlineRecognizer(recognizer); recognizer = nullptr; }
    
    currentText = "";
}
