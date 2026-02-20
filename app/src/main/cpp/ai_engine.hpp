#ifndef AI_ENGINE_H
#define AI_ENGINE_H

#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include "vosk_api.h" // Vosk Header

class AIEngine {
public:
    AIEngine();
    ~AIEngine();

    bool init(const std::string& modelPath);
    void pushAudio(const std::vector<float>& pcm32);
    std::string getCurrentSubtitle();
    void stop();

private:
    VoskModel* model = nullptr;
    VoskRecognizer* recognizer = nullptr;
    
    std::mutex audioMutex;
    std::vector<float> audioBuffer;
    std::atomic<bool> isRunning;
    std::string currentText;
    
    void processingLoop();
    std::string extractTextFromJson(const std::string& json);
};

#endif
