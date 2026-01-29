#include "torrent_system.hpp"
#include <android/log.h>
#include <chrono>
#include <cstring>
#include <random>

#define TAG "StreamX_Core"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

TorrentSystem::TorrentSystem() {
    isRunning = false;
    std::lock_guard<std::mutex> lock(statusMutex);
    currentStatus.progress = 0;
    currentStatus.speed = 0;
    currentStatus.seeds = 0;
    currentStatus.peers = 0;
    currentStatus.state = 0;
    memset(currentStatus.videoPath, 0, 512);
}

TorrentSystem::~TorrentSystem() {
    stop();
}

void TorrentSystem::start(const std::string& magnet, const std::string& saveDir) {
    if (isRunning) stop();

    isRunning = true;
    currentSaveDir = saveDir;
    
    {
        std::lock_guard<std::mutex> lock(statusMutex);
        currentStatus.state = 1; // Preparing
    }

    workerThread = std::thread(&TorrentSystem::loop, this);
    LOGD("Native Engine Started for: %s", magnet.c_str());
}

void TorrentSystem::stop() {
    isRunning = false;
    if (workerThread.joinable()) {
        workerThread.join();
    }
    
    std::lock_guard<std::mutex> lock(statusMutex);
    currentStatus.state = 0; // Idle
    currentStatus.progress = 0;
    LOGD("Native Engine Stopped");
}

EngineStatus TorrentSystem::getStatus() {
    std::lock_guard<std::mutex> lock(statusMutex);
    return currentStatus;
}

void TorrentSystem::loop() {
    runSimulation();
}

void TorrentSystem::runSimulation() {
    LOGD("Fetching Metadata...");
    std::this_thread::sleep_for(std::chrono::seconds(3)); 

    if (!isRunning) return;

    {
        std::lock_guard<std::mutex> lock(statusMutex);
        currentStatus.state = 2; // Downloading
        currentStatus.seeds = 25;
        currentStatus.peers = 10;
        
        // ফাইল পাথ সেটআপ
        std::string fakeFile = currentSaveDir + "/stream_video.mp4";
        strncpy(currentStatus.videoPath, fakeFile.c_str(), 511);
    }

    float progress = 0.0f;
    std::default_random_engine generator;
    std::uniform_int_distribution<int> distribution(1024*512, 1024*1024*5); // 512KB to 5MB

    while (isRunning && progress < 100.0f) {
        std::this_thread::sleep_for(std::chrono::milliseconds(800));
        
        progress += 1.2f;
        if (progress > 100.0f) progress = 100.0f;

        {
            std::lock_guard<std::mutex> lock(statusMutex);
            currentStatus.progress = (int)progress;
            currentStatus.speed = distribution(generator);
            
            // বাফার যদি ১০% এর বেশি হয় তবে প্লেয়ারকে Ready সিগন্যাল দাও
            if (progress >= 10.0f && currentStatus.state != 3) {
                currentStatus.state = 3; // Ready for streaming
                LOGD("Buffer sufficient. Ready to play.");
            }
        }
    }
}
