#include "torrent_system.hpp"
#include <android/log.h>
#include <libtorrent/settings_pack.hpp>
#include <libtorrent/torrent_info.hpp>
#include <libtorrent/add_torrent_params.hpp>
#include <libtorrent/magnet_uri.hpp>
#include <libtorrent/file_storage.hpp>
#include <utility>
#include <thread>

#define TAG "StreamX_Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

TorrentSystem::TorrentSystem() : isRunning(false), ses(nullptr) {
    lt::settings_pack pack;

    // ── Peer/DHT discovery ────────────────────────────────────
    pack.set_bool(lt::settings_pack::enable_dht,    true);
    pack.set_bool(lt::settings_pack::enable_lsd,    true);
    pack.set_bool(lt::settings_pack::enable_upnp,   true);
    pack.set_bool(lt::settings_pack::enable_natpmp, true);

    // Dynamic port (0 = OS picks)
    pack.set_str(lt::settings_pack::listen_interfaces, "0.0.0.0:0,[::]:0");

    // ── Streaming optimisations ───────────────────────────────
    pack.set_bool(lt::settings_pack::prioritize_partial_pieces, true);
    pack.set_int(lt::settings_pack::alert_mask, lt::alert::all_categories);
    pack.set_int(lt::settings_pack::download_rate_limit, 0);   // unlimited
    pack.set_int(lt::settings_pack::upload_rate_limit,   0);
    pack.set_int(lt::settings_pack::active_downloads,    10);
    pack.set_int(lt::settings_pack::metadata_token_limit, 2048); // allow more token exchanges

    // ── DHT tuning ────────────────────────────────────────────
    pack.set_int(lt::settings_pack::dht_max_peers,          2000);
    pack.set_int(lt::settings_pack::dht_announce_interval,    60);

    // ── VERIFIED DHT bootstrap nodes (fake nodes removed) ────
    // Old code had invented domains like dht.metautr.ent / dht.ikig.ail
    // / dht.free.isp / dht.bt.bt — those cause DNS failures and slow
    // down DHT bootstrap.  Only real, publicly-known nodes are kept.
    pack.set_str(lt::settings_pack::dht_bootstrap_nodes,
        "router.bittorrent.com:6881,"
        "router.utorrent.com:6881,"
        "dht.transmissionbt.com:6881,"
        "dht.libtorrent.org:25401");

    ses = new lt::session(pack);

    // Also add them manually for immediate use
    ses->add_dht_node({"router.bittorrent.com",  6881});
    ses->add_dht_node({"router.utorrent.com",    6881});
    ses->add_dht_node({"dht.transmissionbt.com", 6881});
    ses->add_dht_node({"dht.libtorrent.org",    25401});

    LOGD("TorrentSystem constructed (clean DHT nodes)");
}

TorrentSystem::~TorrentSystem() {
    stop();
    if (ses) { delete ses; ses = nullptr; }
}

void TorrentSystem::start(const std::string& magnet, const std::string& saveDir) {
    stop();

    isRunning    = true;
    finalFilePath = "";
    currentStatus = {0, 0, 0, 0, 0, ""};

    lt::error_code ec;
    lt::add_torrent_params p = lt::parse_magnet_uri(magnet, ec);
    if (ec) {
        LOGE("Parse magnet error: %s", ec.message().c_str());
        currentStatus.state = 4;
        return;
    }

    p.save_path  = saveDir;
    p.flags     &= ~lt::torrent_flags::paused;
    p.flags     &= ~lt::torrent_flags::auto_managed;
    p.flags     |=  lt::torrent_flags::sequential_download;  // ← critical for streaming

    // Real, working trackers only
    static const std::vector<std::string> extra_trackers = {
        "http://tracker.bt4g.com:2095/announce",
        "http://tracker.files.fm:6969/announce",
        "http://tracker.gbitt.info:80/announce",
        "https://tracker.bt4g.com:443/announce",
        "https://tracker.nanoha.org:443/announce",
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.openbittorrent.com:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://tracker.leechers-paradise.org:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.moeking.me:6969/announce"
    };
    for (const auto& tr : extra_trackers) p.trackers.push_back(tr);

    handle = ses->add_torrent(p, ec);
    if (ec) {
        LOGE("Add torrent error: %s", ec.message().c_str());
        currentStatus.state = 4;
        return;
    }

    handle.resume();
    handle.force_reannounce();
    handle.force_dht_announce();
    LOGD("Torrent added with %zu trackers", p.trackers.size());

    workerThread = std::thread(&TorrentSystem::updateLoop, this);
}

void TorrentSystem::updateLoop() {
    int reannounce_counter = 0;

    while (isRunning) {
        if (!ses || !handle.is_valid()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(500));
            continue;
        }

        lt::torrent_status s = handle.status();

        std::lock_guard<std::mutex> lock(statusMutex);

        currentStatus.progress = (s.progress > 0) ? static_cast<int>(s.progress * 100) : 0;
        currentStatus.speed    = s.download_rate;
        currentStatus.seeds    = s.num_seeds;
        currentStatus.peers    = s.num_peers;

        if (!s.has_metadata) {
            // ── State 1: Fetching metadata ────────────────────
            currentStatus.state = 1;
            LOGD("Fetching metadata  peers=%d", s.num_peers);

            // Re-announce every ~2 s until we get metadata
            if (++reannounce_counter % 8 == 0) {
                handle.force_reannounce();
                handle.force_dht_announce();
            }
        }
        else if (s.state == lt::torrent_status::downloading ||
                 s.state == lt::torrent_status::finished    ||
                 s.state == lt::torrent_status::seeding) {

            if (finalFilePath.empty()) {
                // ── Identify largest file (= the video) ───────
                std::shared_ptr<const lt::torrent_info> info = handle.torrent_file();
                if (info && info->is_valid()) {
                    int     largestIdx  = 0;
                    int64_t maxSize     = 0;

                    for (int idx = 0; idx < info->num_files(); ++idx) {
                        lt::file_index_t fi(idx);
                        int64_t sz = info->files().file_size(fi);
                        if (sz > maxSize) { maxSize = sz; largestIdx = idx; }
                    }

                    lt::file_index_t largest_fi(largestIdx);

                    // Build absolute path
                    std::string rel = info->files().file_path(largest_fi);
                    finalFilePath   = s.save_path + "/" + rel;
                    strncpy(currentStatus.videoPath, finalFilePath.c_str(), 511);

                    // ── Streaming piece priorities ─────────────
                    // Give the first 20 and last 10 pieces top priority
                    // so MPV can read the video header (and, for MP4, the
                    // moov atom which may be at the end) immediately.
                    lt::peer_request first_byte = info->map_file(largest_fi, 0, 1);
                    lt::peer_request last_byte  = info->map_file(largest_fi, maxSize - 1, 1);
                    int first_piece = (int)first_byte.piece;
                    int last_piece  = (int)last_byte.piece;

                    for (int i = 0; i < 20 && first_piece + i <= last_piece; ++i)
                        handle.piece_priority(lt::piece_index_t(first_piece + i), lt::top_priority);
                    for (int i = 0; i < 10 && last_piece - i >= first_piece; ++i)
                        handle.piece_priority(lt::piece_index_t(last_piece - i), lt::top_priority);

                    // Default priority for the rest
                    handle.file_priority(largest_fi, lt::default_priority);

                    LOGD("Metadata ready → %s  (pieces %d–%d top priority)",
                         finalFilePath.c_str(), first_piece, first_piece + 19);

                    handle.force_reannounce();
                    handle.force_dht_announce();
                }
            }

            // ── State 3: Ready to stream ──────────────────────
            // FIX: Start playback as soon as we have the file path and
            // libtorrent has downloaded anything at all.  The old threshold
            // was `progress >= 1%` which = ~20 MB for a 2 GB movie.
            // MPV's internal cache (`demuxer-readahead-secs`) handles the
            // actual buffering — we just need to hand it the file.
            if (!finalFilePath.empty() && s.total_done > 0) {
                currentStatus.state = 3;
                LOGD("State: Ready  progress=%d%%  downloaded=%lld bytes",
                     currentStatus.progress, (long long)s.total_done);
            } else {
                currentStatus.state = 2;
                LOGD("State: Buffering  progress=%d%%", currentStatus.progress);
            }
        }
        else {
            currentStatus.state = 0;
            LOGD("State: Idle");
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(250));
    }
}

void TorrentSystem::stop() {
    isRunning = false;
    if (workerThread.joinable()) workerThread.join();
    if (ses && handle.is_valid()) {
        ses->remove_torrent(handle, lt::session::delete_files);
        LOGD("Torrent stopped and files removed");
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
