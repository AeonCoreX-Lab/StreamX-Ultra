#ifndef AI_ENGINE_H
#define AI_ENGINE_H

#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <thread> // <-- Thread header added
#include "sherpa-onnx/c-api/c-api.h"

class AIEngine {
public:
    AIEngine();
    ~AIEngine();

    bool init(const std::string& modelPath);
    void pushAudio(const std::vector<float>& pcm32);
    std::string getCurrentSubtitle();
    void stop();

private:
    const SherpaOnnxOnlineRecognizer* recognizer = nullptr;
    const SherpaOnnxOnlineStream* stream = nullptr;
    
    std::mutex audioMutex;
    std::vector<float> audioBuffer;
    std::atomic<bool> isRunning;
    std::string currentText;
    
    std::thread workerThread; // <-- Worker thread added for safe shutdown
    
    void processingLoop();
};

#endif
