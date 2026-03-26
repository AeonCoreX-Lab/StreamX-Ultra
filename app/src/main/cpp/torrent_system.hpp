#ifndef TORRENT_SYSTEM_H
#define TORRENT_SYSTEM_H

#include <string>
#include <atomic>
#include <thread>
#include <mutex>
#include <vector>

#include <libtorrent/session.hpp>
#include <libtorrent/torrent_handle.hpp>
#include <libtorrent/magnet_uri.hpp>
#include <libtorrent/alert_types.hpp>

struct EngineStatus {
    int  progress;
    long speed;
    int  seeds;
    int  peers;
    int  state;           // 0=Idle 1=Metadata 2=Buffering 3=Ready 4=Error
    char videoPath[512];
};

class TorrentSystem {
public:
    TorrentSystem();
    ~TorrentSystem();

    void start(const std::string& magnet, const std::string& saveDir);
    void stop();
    EngineStatus getStatus();
    std::string  getFilePath();

private:
    std::atomic<bool> isRunning;
    std::thread       workerThread;
    std::mutex        statusMutex;

    lt::session*       ses;
    lt::torrent_handle handle;

    EngineStatus currentStatus;
    std::string  finalFilePath;

    // ── Piece range of the video file ──────────────────────────
    // Stored once metadata is available so updateLoop() can call
    // handle.have_piece() without re-computing every iteration.
    int firstPieceIdx = -1;
    int lastPieceIdx  = -1;

    void updateLoop();
};

#endif
