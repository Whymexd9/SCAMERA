#pragma once
#include "vivo-raw-handoff.h"
#include <chrono>
#include <cerrno>
#include <poll.h>
#include <sys/socket.h>
#include <fcntl.h>
#include <unistd.h>

namespace vivo_raw {
class Channel {
    int fd_;
    std::chrono::steady_clock::time_point deadline_;
    bool finished_=false;
    void closeSocket() {
        if(fd_>=0){close(fd_);fd_=-1;}
    }
    void wait(short events) {
        for(;;) {
            const auto remaining=std::chrono::duration_cast<std::chrono::milliseconds>(
                deadline_-std::chrono::steady_clock::now()).count();
            if(remaining<=0)throw std::runtime_error("RAW transfer timed out");
            pollfd item{fd_,events,0};
            int result=poll(&item,1,int(remaining));
            if(result<0 && errno==EINTR)continue;
            if(result<=0)throw std::runtime_error("RAW transfer wait failed or timed out");
            if(item.revents&POLLNVAL)throw std::runtime_error("RAW socket closed");
            return;
        }
    }
    size_t readSome(uint8_t* data,size_t size) {
        for(;;) {
            wait(POLLIN);
            ssize_t count=recv(fd_,data,size,MSG_DONTWAIT);
            if(count<0 && (errno==EINTR || errno==EAGAIN || errno==EWOULDBLOCK))continue;
            if(count<0)throw std::runtime_error("RAW socket read failed");
            return size_t(count);
        }
    }
    void readExact(uint8_t* data,size_t size) {
        while(size) {
            const size_t count=readSome(data,size);
            if(!count)throw std::runtime_error("Truncated RAW transfer");
            data+=count;size-=count;
        }
    }
public:
    // Takes ownership, including on constructor failure. The caller authenticates
    // the intended process when creating the socket; UID is an additional check.
    Channel(int ownedSocket,uid_t expectedUid,std::chrono::milliseconds timeout=std::chrono::seconds(60))
        :fd_(ownedSocket),deadline_(std::chrono::steady_clock::now()+timeout) {
        try {
            if(timeout.count()<=0 || timeout>std::chrono::seconds(60))
                throw std::invalid_argument("Invalid RAW transfer deadline");
            int type=0;socklen_t length=sizeof(type);
            sockaddr_storage address{};socklen_t addressLength=sizeof(address);
            ucred peer{};socklen_t peerLength=sizeof(peer);
            if(fd_<0 || getsockopt(fd_,SOL_SOCKET,SO_TYPE,&type,&length) || type!=SOCK_STREAM ||
               getsockname(fd_,reinterpret_cast<sockaddr*>(&address),&addressLength) || address.ss_family!=AF_UNIX ||
               getsockopt(fd_,SOL_SOCKET,SO_PEERCRED,&peer,&peerLength) ||
               peerLength!=sizeof(peer) || peer.uid!=expectedUid || peer.pid<=0)
                throw std::runtime_error("Unexpected RAW socket or peer");
            int flags=fcntl(fd_,F_GETFD);
            if(flags<0 || fcntl(fd_,F_SETFD,flags|FD_CLOEXEC)<0)
                throw std::runtime_error("Cannot protect RAW socket inheritance");
        } catch(...) {closeSocket();throw;}
    }
    Channel(const Channel&)=delete;
    Channel& operator=(const Channel&)=delete;
    ~Channel(){closeSocket();}
    void sendPacket(const uint8_t* packet,size_t size) {
        try {
            if(finished_ || fd_<0)throw std::logic_error("RAW sender closed");
            // The source must remain immutable until all bytes have been copied.
            (void)decode(packet,size);
            while(size) {
                wait(POLLOUT);
                ssize_t count=send(fd_,packet,size,MSG_DONTWAIT|MSG_NOSIGNAL);
                if(count<0 && (errno==EINTR || errno==EAGAIN || errno==EWOULDBLOCK))continue;
                if(count<=0)throw std::runtime_error("RAW socket write failed");
                packet+=count;size-=size_t(count);
            }
        } catch(...) {closeSocket();throw;}
    }
    void finishSending() {
        if(finished_ || fd_<0 || shutdown(fd_,SHUT_WR)) {
            closeSocket();throw std::runtime_error("Cannot finish RAW transfer");
        }
        finished_=true;
    }
    void receiveBurst(Burst& transaction) {
        try {
            if(finished_ || fd_<0)throw std::logic_error("RAW receiver closed");
            for(unsigned i=0;i<7;i++) {
                std::vector<uint8_t> packet(HeaderBytes);
                readExact(packet.data(),packet.size());
                auto word=[&](size_t p){return uint32_t(packet[p])|uint32_t(packet[p+1])<<8|
                    uint32_t(packet[p+2])<<16|uint32_t(packet[p+3])<<24;};
                const uint32_t payload=word(12);
                if(word(0)!=0x31465256 || word(4)!=2 || word(8)!=HeaderBytes ||
                   !payload || payload>MaxPayload)
                    throw std::runtime_error("Invalid RAW channel header");
                packet.resize(size_t(HeaderBytes)+payload);
                readExact(packet.data()+HeaderBytes,payload);
                transaction.accept(packet.data(),packet.size());
            }
            uint8_t extra=0;
            if(readSome(&extra,1))throw std::runtime_error("Trailing RAW transfer data");
            finished_=true;closeSocket();
        } catch(...) {transaction.cancel();closeSocket();throw;}
    }
};
} // namespace vivo_raw
