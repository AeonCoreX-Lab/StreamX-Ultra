#include "ai_engine.hpp"
#include <thread>
#include <android/log.h>

#define TAG "StreamX_AI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

AIEngine::AIEngine() : isRunning(false) {}

AIEngine::~AIEngine() { stop(); }

bool AIEngine::init(const std::string& modelPath) {
    struct whisper_context_params cparams = whisper_context_default_params();
    ctx = whisper_init_from_file_with_params(modelPath.c_str(), cparams);

    if (!ctx) {
        LOGD("Failed to load Whisper model from: %s", modelPath.c_str());
        return false;
    }
    
    isRunning = true;
    std::thread(&AIEngine::processingLoop, this).detach();
    return true;
}

void AIEngine::pushAudio(const std::vector<float>& pcm32) {
    std::lock_guard<std::mutex> lock(audioMutex);
    // বাফারে নতুন অডিও যোগ করা হচ্ছে
    audioBuffer.insert(audioBuffer.end(), pcm32.begin(), pcm32.end());
    
    // বাফার খুব বড় যাতে না হয় (মেমোরি সেফটি)
    if (audioBuffer.size() > 16000 * 30) { // ৩০ সেকেন্ডের বেশি জমলে পুরনো ডিলিট
        audioBuffer.erase(audioBuffer.begin(), audioBuffer.begin() + (16000 * 5));
    }
}

void AIEngine::processingLoop() {
    while (isRunning) {
        std::vector<float> processBuffer;
        
        {
            std::lock_guard<std::mutex> lock(audioMutex);
            if (audioBuffer.size() >= 16000 * 3) { // ৩ সেকেন্ড অডিও জমলে প্রসেস শুরু
                processBuffer = audioBuffer;
                // প্রসেস হওয়া অডিও রেখে দেওয়া হচ্ছে কনটেক্সটের জন্য (Sliding Window)
            }
        }

        if (!processBuffer.empty()) {
            whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
            wparams.print_progress = false;
            wparams.language = "auto"; // অটো ডিটেক্ট ল্যাঙ্গুয়েজ
            
            if (whisper_full(ctx, wparams, processBuffer.data(), processBuffer.size()) == 0) {
                const int n_segments = whisper_full_n_segments(ctx);
                std::string fullText = "";
                for (int i = 0; i < n_segments; ++i) {
                    fullText += whisper_full_get_segment_text(ctx, i);
                }
                currentText = fullText;
                LOGD("AI Subtitle: %s", currentText.c_str());
            }
        }
        
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }
}

std::string AIEngine::getCurrentSubtitle() {
    return currentText;
}

void AIEngine::stop() {
    isRunning = false;
    if (ctx) {
        whisper_free(ctx);
        ctx = nullptr;
    }
}
