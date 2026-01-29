#ifndef TORRENT_SYSTEM_H
#define TORRENT_SYSTEM_H

#include <string>
#include <atomic>
#include <thread>
#include <vector>
#include <mutex>

// ইঞ্জিনের বর্তমান অবস্থা
struct EngineStatus {
    int progress;
    long speed;       // bytes per second
    int seeds;
    int peers;
    int state;        // 0=Idle, 1=Prep, 2=Downloading, 3=Ready, 4=Error
    char videoPath[512]; 
};

class TorrentSystem {
public:
    TorrentSystem();
    ~TorrentSystem();

    // মেইন কমান্ডস
    void start(const std::string& magnet, const std::string& saveDir);
    void stop();
    EngineStatus getStatus();

private:
    std::atomic<bool> isRunning;
    std::thread workerThread;
    std::mutex statusMutex; // মাল্টি-থ্রেডিং সেফটির জন্য
    
    EngineStatus currentStatus;
    std::string currentSaveDir;

    void loop();
    void runSimulation(); 
    void updateState(int state, int progress = -1);
};

#endif //TORRENT_SYSTEM_H
