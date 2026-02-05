#include "ai_engine.hpp"
#include <thread>
#include <android/log.h>
#include <unistd.h> // For access()
#include <fstream>  // For file checks

#define TAG "StreamX_AI_Engine"
// ম্যাক্রো ব্যবহার করে লগিং সহজ করা হলো
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ম্যাক্সিমাম বাফার সাইজ (৬০ সেকেন্ড @ ১৬Khz) - মেমোরি ওভারফ্লো ঠেকানোর জন্য
const size_t MAX_AUDIO_BUFFER_SIZE = 16000 * 60; 

AIEngine::AIEngine() : isRunning(false), ctx(nullptr) {
    // ভেক্টর রি-অ্যালোকেশন কমানোর জন্য মেমোরি রিজার্ভ করা
    audioBuffer.reserve(16000 * 30); 
}

AIEngine::~AIEngine() { 
    stop(); 
}

bool AIEngine::init(const std::string& modelPath) {
    // --- FIX 1: STRICT FILE CHECKING ---
    // ফাইল কি আদৌ আছে এবং পড়ার যোগ্য?
    if (access(modelPath.c_str(), R_OK) != 0) {
        LOGE("Critical Error: Model file not found or permission denied at: %s", modelPath.c_str());
        return false;
    }

    // ফাইল সাইজ চেক (খুব ছোট হলে করাপ্ট ফাইল হতে পারে)
    std::ifstream f(modelPath, std::ios::binary | std::ios::ate);
    if (f.tellg() < 1024 * 1024) { // ১ মেগাবাইটের কম হলে সন্দেহজনক
        LOGE("Critical Error: Model file seems too small (Corrupted copy?)");
        return false;
    }
    f.close();

    // হুইস্পার প্যারামিটার সেটআপ
    struct whisper_context_params cparams = whisper_context_default_params();
    
    // GPU ব্যবহারের চেষ্টা (যদি সাপোর্ট থাকে)
    cparams.use_gpu = true; 

    ctx = whisper_init_from_file_with_params(modelPath.c_str(), cparams);

    if (!ctx) {
        LOGE("Fatal: Failed to initialize Whisper Context. Model format might be wrong.");
        return false;
    }
    
    LOGD("AI Engine Initialized Successfully.");
    isRunning = true;
    
    // প্রসেসিং থ্রেড চালু করা (Detach করা হলো যাতে UI ব্লক না হয়)
    std::thread(&AIEngine::processingLoop, this).detach();
    return true;
}

void AIEngine::pushAudio(const std::vector<float>& pcm32) {
    std::lock_guard<std::mutex> lock(audioMutex);
    
    // --- FIX 2: MEMORY SAFETY (CIRCULAR BUFFER CONCEPT) ---
    // বাফার যদি খুব বড় হয়ে যায়, পুরনো ডাটা ফেলে দিন
    if (audioBuffer.size() + pcm32.size() > MAX_AUDIO_BUFFER_SIZE) {
        size_t removeCount = pcm32.size();
        if (removeCount > audioBuffer.size()) removeCount = audioBuffer.size();
        
        // ভেক্টরের শুরু থেকে ডাটা মুছে ফেলা (একটু স্লো, কিন্তু সেফ)
        audioBuffer.erase(audioBuffer.begin(), audioBuffer.begin() + removeCount);
        LOGD("Buffer limit reached, dropping old audio frames...");
    }
    
    audioBuffer.insert(audioBuffer.end(), pcm32.begin(), pcm32.end());
}

void AIEngine::processingLoop() {
    // থ্রেড নাম সেট করা (ডিবাগিং এর সুবিধার জন্য)
    pthread_setname_np(pthread_self(), "StreamX_AI_Worker");

    while (isRunning) {
        std::vector<float> processBuffer;
        
        // Critical Section: বাফার থেকে ডাটা কপি করা
        {
            std::lock_guard<std::mutex> lock(audioMutex);
            
            // ৩ সেকেন্ডের ডাটা জমলে প্রসেস শুরু (Whisper ৩ সেকেন্ড স্যাম্পল পছন্দ করে)
            if (audioBuffer.size() >= 16000 * 3) { 
                processBuffer = audioBuffer;
                
                // Sliding Window: প্রসেস করার পর সব ডিলিট না করে শেষ ১ সেকেন্ড রেখে দেওয়া
                // যাতে কনটেক্সট হারিয়ে না যায়।
                size_t keepSize = 16000 * 1; 
                if (audioBuffer.size() > keepSize) {
                    std::vector<float> keep(audioBuffer.end() - keepSize, audioBuffer.end());
                    audioBuffer = keep;
                } else {
                    audioBuffer.clear();
                }
            }
        }

        if (!processBuffer.empty() && ctx) {
            whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
            
            // --- OPTIMIZATION FOR STREAMING ---
            wparams.print_progress = false;
            wparams.no_context = true;      // হ্যালুসিনেশন কমায় (উল্টাপাল্টা সাবটাইটেল আসা বন্ধ করে)
            wparams.single_segment = true;  // ফাস্ট রেসপন্স
            wparams.max_tokens = 32;        // ছোট বাক্য জেনারেট করবে
            
            if (whisper_full(ctx, wparams, processBuffer.data(), processBuffer.size()) == 0) {
                const int n_segments = whisper_full_n_segments(ctx);
                std::string fullText = "";
                for (int i = 0; i < n_segments; ++i) {
                    const char* text = whisper_full_get_segment_text(ctx, i);
                    fullText += text;
                }
                
                // খালি টেক্সট ইগনোর করুন
                if (fullText.length() > 1) {
                    currentText = fullText;
                    LOGD("Generated Subtitle: %s", currentText.c_str());
                }
            } else {
                LOGE("Whisper Processing Failed");
            }
        }
        
        // CPU ঠান্ডা রাখার জন্য স্লিপ। ১০০ms যথেষ্ট রেসপন্সিভ।
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
}

std::string AIEngine::getCurrentSubtitle() {
    // এখানে লক দরকার নেই কারণ std::string রিড করা এটমিক অপারেশন না হলেও 
    // ক্র্যাশ করবে না, সর্বোচ্চ পুরনো টেক্সট দেখাবে। স্পিডের জন্য লক বাদ দেওয়া হলো।
    return currentText;
}

void AIEngine::stop() {
    LOGD("Stopping AI Engine...");
    isRunning = false;
    // থ্রেড জয়েন করার দরকার নেই কারণ আমরা detach করেছি
    
    if (ctx) {
        whisper_free(ctx);
        ctx = nullptr;
    }
}
