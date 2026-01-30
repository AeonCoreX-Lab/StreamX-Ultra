#ifndef AI_ENGINE_H
#define AI_ENGINE_H

#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include "whisper.h"

class AIEngine {
public:
    AIEngine();
    ~AIEngine();

    bool init(const std::string& modelPath);
    void pushAudio(const std::vector<float>& pcm32);
    std::string getCurrentSubtitle();
    void stop();

private:
    struct whisper_context* ctx = nullptr;
    std::mutex audioMutex;
    std::vector<float> audioBuffer;
    std::atomic<bool> isRunning;
    std::string currentText;
    
    // AI প্রসেসিং লুপ
    void processingLoop();
};

#endif
