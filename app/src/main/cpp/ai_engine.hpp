#ifndef AI_ENGINE_H
#define AI_ENGINE_H

#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include "sherpa-onnx/c-api/c-api.h" // Sherpa-ONNX Header

class AIEngine {
public:
    AIEngine();
    ~AIEngine();

    bool init(const std::string& modelPath);
    void pushAudio(const std::vector<float>& pcm32);
    std::string getCurrentSubtitle();
    void stop();

private:
    // ---> FIX: Added 'const' to match the return type of Sherpa C-API <---
    const SherpaOnnxOnlineRecognizer* recognizer = nullptr;
    const SherpaOnnxOnlineStream* stream = nullptr;
    
    std::mutex audioMutex;
    std::vector<float> audioBuffer;
    std::atomic<bool> isRunning;
    std::string currentText;
    
    void processingLoop();
};

#endif
