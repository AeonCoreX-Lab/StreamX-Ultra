#include "ai_engine.hpp"
#include <thread>
#include <android/log.h>
#include <unistd.h>
#include <fstream>
#include <cmath>

#define TAG "StreamX_AI_Engine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

const size_t MAX_AUDIO_BUFFER_SIZE = 16000 * 60; 

AIEngine::AIEngine() : isRunning(false), model(nullptr), recognizer(nullptr) {
    audioBuffer.reserve(16000 * 10); 
}

AIEngine::~AIEngine() { stop(); }

bool AIEngine::init(const std::string& modelPath) {
    // Vosk modelPath হলো একটি ডিরেক্টরি (ফোল্ডার), কোনো একক ফাইল নয়।
    if (access(modelPath.c_str(), R_OK) != 0) {
        LOGE("Critical Error: Vosk Model folder not found at: %s", modelPath.c_str());
        return false;
    }

    vosk_set_log_level(-1); // 불필요한 로그 숨기기 (Hide unnecessary logs)
    
    model = vosk_model_new(modelPath.c_str());
    if (!model) {
        LOGE("Fatal: Failed to load Vosk Model.");
        return false;
    }
    
    // 16000 Hz স্যাম্পল রেট
    recognizer = vosk_recognizer_new(model, 16000.0);
    
    LOGD("Vosk AI Engine Initialized Successfully.");
    isRunning = true;
    
    std::thread(&AIEngine::processingLoop, this).detach();
    return true;
}

void AIEngine::pushAudio(const std::vector<float>& pcm32) {
    std::lock_guard<std::mutex> lock(audioMutex);
    
    if (audioBuffer.size() + pcm32.size() > MAX_AUDIO_BUFFER_SIZE) {
        size_t removeCount = pcm32.size();
        if (removeCount > audioBuffer.size()) removeCount = audioBuffer.size();
        audioBuffer.erase(audioBuffer.begin(), audioBuffer.begin() + removeCount);
    }
    audioBuffer.insert(audioBuffer.end(), pcm32.begin(), pcm32.end());
}

// Simple JSON parser to avoid importing heavy libraries
std::string AIEngine::extractTextFromJson(const std::string& json) {
    std::string targetText = "";
    size_t pos = json.find("\"partial\" : \"");
    if (pos == std::string::npos) {
        pos = json.find("\"text\" : \"");
        if (pos != std::string::npos) pos += 10;
    } else {
        pos += 13;
    }

    if (pos != std::string::npos) {
        size_t endPos = json.find("\"", pos);
        if (endPos != std::string::npos) {
            targetText = json.substr(pos, endPos - pos);
        }
    }
    return targetText;
}

void AIEngine::processingLoop() {
    pthread_setname_np(pthread_self(), "StreamX_Vosk_Worker");

    while (isRunning) {
        std::vector<float> processBuffer;
        
        {
            std::lock_guard<std::mutex> lock(audioMutex);
            // ০.৫ সেকেন্ড ডাটা পেলেই প্রসেস শুরু (রিয়েল-টাইম রেসপন্স)
            if (audioBuffer.size() >= 16000 * 0.5) { 
                processBuffer = audioBuffer;
                audioBuffer.clear();
            }
        }

        if (!processBuffer.empty() && recognizer) {
            // Convert Float32 to Int16 for Vosk
            std::vector<int16_t> pcm16(processBuffer.size());
            for (size_t i = 0; i < processBuffer.size(); ++i) {
                float v = processBuffer[i];
                if (v > 1.0f) v = 1.0f;
                if (v < -1.0f) v = -1.0f;
                pcm16[i] = static_cast<int16_t>(v * 32767.0f);
            }

            int state = vosk_recognizer_accept_waveform(recognizer, (const char*)pcm16.data(), pcm16.size() * 2);
            const char* result = state ? vosk_recognizer_result(recognizer) : vosk_recognizer_partial_result(recognizer);
            
            if (result) {
                std::string text = extractTextFromJson(result);
                if (!text.empty()) {
                    currentText = text;
                    LOGD("Vosk Subtitle: %s", currentText.c_str());
                }
            }
        }
        
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
}

std::string AIEngine::getCurrentSubtitle() {
    return currentText;
}

void AIEngine::stop() {
    LOGD("Stopping Vosk AI Engine...");
    isRunning = false;
    
    if (recognizer) { vosk_recognizer_free(recognizer); recognizer = nullptr; }
    if (model) { vosk_model_free(model); model = nullptr; }
}
