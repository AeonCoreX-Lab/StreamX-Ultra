#include "torrent_system.hpp"
#include <android/log.h>
#include <libtorrent/settings_pack.hpp>
#include <libtorrent/torrent_info.hpp>
#include <libtorrent/add_torrent_params.hpp>

#define TAG "StreamX_Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

TorrentSystem::TorrentSystem() : isRunning(false), ses(nullptr) {
    lt::settings_pack pack;
    
    // FIX: Enable Aggressive DHT and Peer Discovery for Instant Metadata
    pack.set_bool(lt::settings_pack::enable_dht, true);
    pack.set_bool(lt::settings_pack::enable_lsd, true);
    pack.set_bool(lt::settings_pack::enable_upnp, true);
    pack.set_bool(lt::settings_pack::enable_natpmp, true);
    pack.set_str(lt::settings_pack::listen_interfaces, "0.0.0.0:6881");
    pack.set_bool(lt::settings_pack::prioritize_partial_pieces, true);
    pack.set_int(lt::settings_pack::metadata_token_limit, 500);

    pack.set_int(lt::settings_pack::alert_mask, lt::alert::all_categories);
    pack.set_int(lt::settings_pack::download_rate_limit, 0); 
    pack.set_int(lt::settings_pack::upload_rate_limit, 0);
    
    ses = new lt::session(pack);
    memset(&currentStatus, 0, sizeof(EngineStatus));
}

TorrentSystem::~TorrentSystem() {
    stop();
    if (ses != nullptr) {
        delete ses;
        ses = nullptr;
    }
}

void TorrentSystem::start(const std::string& magnet, const std::string& saveDir) {
    if (isRunning) stop();
    
    LOGD("Starting Engine for: %s", magnet.c_str());

    finalFilePath = ""; 
    memset(&currentStatus, 0, sizeof(EngineStatus));

    lt::add_torrent_params p;
    lt::error_code ec;
    lt::parse_magnet_uri(magnet, p, ec);
    
    if (ec) {
        LOGD("Magnet Parse Error: %s", ec.message().c_str());
        return;
    }

    p.save_path = saveDir;
    // FIX: Force Sequential Download right from the start
    p.flags |= lt::torrent_flags::sequential_download;
    
    handle = ses->add_torrent(p);
    
    isRunning = true;
    workerThread = std::thread(&TorrentSystem::updateLoop, this);
}

void TorrentSystem::updateLoop() {
    while (isRunning) {
        if (ses == nullptr || !handle.is_valid()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(500));
            continue;
        }

        std::vector<lt::alert*> alerts;
        ses->pop_alerts(&alerts);

        lt::torrent_status s = handle.status();

        std::lock_guard<std::mutex> lock(statusMutex);
        currentStatus.progress = (int)(s.progress * 100);
        currentStatus.speed = s.download_payload_rate;
        currentStatus.seeds = s.num_seeds;
        currentStatus.peers = s.num_peers;

        if (s.state == lt::torrent_status::checking_files || s.state == lt::torrent_status::downloading_metadata) {
            currentStatus.state = 1; 
        } 
        else if (s.state == lt::torrent_status::downloading || s.state == lt::torrent_status::finished) {
            
            if (finalFilePath.empty() && s.has_metadata) {
                handle.resume(); // Enforce active downloading
                handle.set_sequential_download(true); // Double check sequential
                
                auto info = s.torrent_file.lock();
                if (info && info->num_files() > 0) {
                    lt::file_index_t largestFileIdx(0);
                    std::int64_t maxSize = 0;
                    
                    for (int i = 0; i < info->num_files(); ++i) {
                        lt::file_index_t idx(i);
                        if (info->files().file_size(idx) > maxSize) {
                            maxSize = info->files().file_size(idx);
                            largestFileIdx = idx;
                        }
                    }
                    
                    std::string relPath = info->files().file_path(largestFileIdx);
                    finalFilePath = s.save_path + "/" + relPath;
                    
                    strncpy(currentStatus.videoPath, finalFilePath.c_str(), 511);
                    handle.file_priority(largestFileIdx, lt::default_priority);
                }
            }

            // FIX: Play video at 1% instead of 5% for instant playback
            if (currentStatus.progress >= 1 && !finalFilePath.empty()) {
                currentStatus.state = 3; 
            } else {
                currentStatus.state = 2; 
            }
        } 
        else {
            currentStatus.state = 0;
        }

        // Reduced sleep for faster UI updates
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
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
