#include "torrent_system.hpp"
#include <android/log.h>
#include <libtorrent/settings_pack.hpp>
#include <libtorrent/torrent_info.hpp>
#include <libtorrent/add_torrent_params.hpp>
#include <libtorrent/magnet_uri.hpp> // Required for proper magnet parsing

#define TAG "StreamX_Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

TorrentSystem::TorrentSystem() : isRunning(false), ses(nullptr) {
    lt::settings_pack pack;
    
    // Enable Aggressive DHT and Peer Discovery for Instant Metadata
    pack.set_bool(lt::settings_pack::enable_dht, true);
    pack.set_bool(lt::settings_pack::enable_lsd, true);
    pack.set_bool(lt::settings_pack::enable_upnp, true);
    pack.set_bool(lt::settings_pack::enable_natpmp, true);
    
    // FIX: Use dynamic ports to prevent binding issues on Android
    pack.set_str(lt::settings_pack::listen_interfaces, "0.0.0.0:0,[::]:0");
    
    pack.set_bool(lt::settings_pack::prioritize_partial_pieces, true);
    pack.set_int(lt::settings_pack::metadata_token_limit, 500);

    pack.set_int(lt::settings_pack::alert_mask, lt::alert::all_categories);
    pack.set_int(lt::settings_pack::download_rate_limit, 0); 
    pack.set_int(lt::settings_pack::upload_rate_limit, 0);
    
    // Increased active downloads for faster peer connecting
    pack.set_int(lt::settings_pack::active_downloads, 10);
    
    ses = new lt::session(pack);

    // FIX: Essential DHT bootstrap routers. Without this, magnet metadata will hang!
    ses->add_dht_router({"router.bittorrent.com", 6881});
    ses->add_dht_router({"router.utorrent.com", 6881});
    ses->add_dht_router({"dht.transmissionbt.com", 6881});
    ses->add_dht_router({"dht.libtorrent.org", 25401});
    ses->add_dht_router({"dht.aelitis.com", 6881});
}

TorrentSystem::~TorrentSystem() {
    stop();
    if (ses) {
        delete ses;
        ses = nullptr;
    }
}

void TorrentSystem::start(const std::string& magnet, const std::string& saveDir) {
    stop(); 

    isRunning = true;
    finalFilePath = "";

    currentStatus = {0, 0, 0, 0, 0, ""};
    
    lt::error_code ec;
    
    // FIX: Parse magnet URI properly using libtorrent 2.x standards
    lt::add_torrent_params p = lt::parse_magnet_uri(magnet, ec);
    if (ec) {
        LOGD("Parse Magnet Error: %s", ec.message().c_str());
        currentStatus.state = 4; // Error State
        return;
    }
    
    p.save_path = saveDir;
    handle = ses->add_torrent(p, ec);
    
    if (ec) {
        LOGD("Add Torrent Error: %s", ec.message().c_str());
        currentStatus.state = 4; // Error State
        return;
    }

    workerThread = std::thread(&TorrentSystem::updateLoop, this);
}

void TorrentSystem::updateLoop() {
    while (isRunning) {
        if (ses == nullptr || !handle.is_valid()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(500));
            continue;
        }

        lt::torrent_status s = handle.status();

        std::lock_guard<std::mutex> lock(statusMutex);
        
        currentStatus.progress = (s.progress > 0) ? static_cast<int>(s.progress * 100) : 0;
        currentStatus.speed = s.download_rate;
        currentStatus.seeds = s.num_seeds;
        currentStatus.peers = s.num_peers;

        // FIX: Ensure accurate checking of metadata presence
        if (!s.has_metadata) {
            currentStatus.state = 1; // Fetching Metadata
        } 
        else if (s.state == lt::torrent_status::downloading || s.state == lt::torrent_status::finished || s.state == lt::torrent_status::seeding) {
            
            if (finalFilePath.empty()) {
                std::shared_ptr<const lt::torrent_info> info = handle.torrent_file();
                if (info && info->is_valid()) {
                    int largestFileIdx = 0;
                    int64_t maxSize = 0;
                    
                    for (int idx = 0; idx < info->num_files(); ++idx) {
                        if (info->files().file_size(lt::file_index_t(idx)) > maxSize) {
                            maxSize = info->files().file_size(lt::file_index_t(idx));
                            largestFileIdx = idx;
                        }
                    }
                    
                    std::string relPath = info->files().file_path(lt::file_index_t(largestFileIdx));
                    finalFilePath = s.save_path + "/" + relPath;
                    
                    strncpy(currentStatus.videoPath, finalFilePath.c_str(), 511);
                    
                    // High priority for the video file
                    handle.file_priority(lt::file_index_t(largestFileIdx), lt::default_priority);
                    
                    // Essential for instant streaming playback
                    handle.set_flags(lt::torrent_flags::sequential_download);
                }
            }

            // Play video at 1% for instant playback
            if (currentStatus.progress >= 1 && !finalFilePath.empty()) {
                currentStatus.state = 3; // Ready/Playing
            } else {
                currentStatus.state = 2; // Downloading/Buffering
            }
        } 
        else {
            currentStatus.state = 0; // Idle
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(250));
    }
}

void TorrentSystem::stop() {
    isRunning = false;
    
    if (workerThread.joinable()) {
        workerThread.join();
    }
    
    if (ses != nullptr && handle.is_valid()) {
        ses->remove_torrent(handle, lt::session::delete_files); 
    }
}

EngineStatus TorrentSystem::getStatus() {
    std::lock_guard<std::mutex> lock(statusMutex);
    return currentStatus;
}

std::string TorrentSystem::getFilePath() {
    std::lock_guard<std::mutex> lock(statusMutex);
    return finalFilePath;
}
