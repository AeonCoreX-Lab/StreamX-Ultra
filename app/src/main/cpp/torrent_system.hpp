#ifndef TORRENT_SYSTEM_H
#define TORRENT_SYSTEM_H

#include <string>
#include <atomic>
#include <thread>
#include <mutex>
#include <vector>

// Libtorrent Headers
#include <libtorrent/session.hpp>
#include <libtorrent/torrent_handle.hpp>
#include <libtorrent/magnet_uri.hpp>
#include <libtorrent/alert_types.hpp>

struct EngineStatus {
    int progress;
    long speed;       
    int seeds;
    int peers;
    int state;        // 0=Idle, 1=Prep, 2=Downloading, 3=Ready/Playing
    char videoPath[512]; 
};

class TorrentSystem {
public:
    TorrentSystem();
    ~TorrentSystem();

    void start(const std::string& magnet, const std::string& saveDir);
    void stop();
    EngineStatus getStatus();
    std::string getFilePath();

private:
    std::atomic<bool> isRunning;
    std::thread workerThread;
    std::mutex statusMutex;
    
    // Libtorrent Core
    lt::session* ses;
    lt::torrent_handle handle;
    
    EngineStatus currentStatus;
    std::string finalFilePath;

    void updateLoop();
};

#endif
