#include "vivo-raw-channel.h"
#include "vivo-raw-nice-input.h"
#include "vivo_raw_test_packets.h"
#include <sys/wait.h>
#include <iostream>

void exchange(unsigned failure) {
    int sockets[2];check(socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,sockets)==0);
    int small=1024;check(setsockopt(sockets[1],SOL_SOCKET,SO_SNDBUF,&small,sizeof(small))==0);
    pid_t pid=fork();check(pid>=0);
    if(pid==0) {
        close(sockets[0]);
        try {
            vivo_raw::Channel writer(sockets[1],getuid(),std::chrono::seconds(2));
            unsigned sent=0;
            for(unsigned index:{6,3,0,5,2,4,1}) {
                auto bytes=packet(failure==2?0:index);
                if(failure==3)put64(bytes,24,457);
                if(failure==4) {
                    put32(bytes,12,0xffffffff);
                    check(send(sockets[1],bytes.data(),vivo_raw::HeaderBytes,MSG_NOSIGNAL)==vivo_raw::HeaderBytes);
                    _exit(0);
                }
                if(failure==5) {
                    check(send(sockets[1],bytes.data(),13,MSG_NOSIGNAL)==13);
                    _exit(0);
                }
                writer.sendPacket(bytes.data(),bytes.size());
                std::fill(bytes.begin(),bytes.end(),0);
                if(failure==1 && ++sent==6)break;
            }
            if(failure==6) {
                uint8_t extra=42;check(send(sockets[1],&extra,1,MSG_NOSIGNAL)==1);
            }
            writer.finishSending();
            _exit(0);
        } catch(...) {_exit(1);}
    }
    close(sockets[1]);
    vivo_raw::Burst transaction(123,456,3,1,1);
    bool failed=false;
    try {
        vivo_raw::Channel reader(sockets[0],getuid(),std::chrono::seconds(2));
        reader.receiveBurst(transaction);
        vivo_raw::NiceInput owner(transaction);
        auto& input=owner.view();
        check(input.raw[0][0]==200 && input.raw[4][0]==600 && input.raw[6][0]==500);
        check(input.exposure[4]==2 && input.ae[0].timestamp==1002);
    } catch(...) {failed=true;}
    int status=0;check(waitpid(pid,&status,0)==pid && WIFEXITED(status));
    check(failed==(failure!=0));
    if(failure==0)check(WEXITSTATUS(status)==0);
    else rejects([&]{transaction.finish();});
}

int main() {
    for(unsigned failure=0;failure<=6;failure++)exchange(failure);
    int sockets[2];check(socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,sockets)==0);
    {
        vivo_raw::Burst transaction(123,456,3,1,1);
        vivo_raw::Channel reader(sockets[0],getuid(),std::chrono::milliseconds(5));
        rejects([&]{reader.receiveBurst(transaction);});
        rejects([&]{transaction.finish();});
    }
    close(sockets[1]);
    check(socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,sockets)==0);
    rejects([&]{vivo_raw::Channel wrongPeer(sockets[0],getuid()+1);});
    check(fcntl(sockets[0],F_GETFD)==-1 && errno==EBADF);close(sockets[1]);
    check(socketpair(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC,0,sockets)==0);
    rejects([&]{vivo_raw::Channel wrongType(sockets[0],getuid());});
    check(fcntl(sockets[0],F_GETFD)==-1 && errno==EBADF);close(sockets[1]);
    std::cout<<"PASS: cross-process RAW/NICE delivery, partial I/O, independent pixels, missing/duplicate/foreign frames, oversized header, truncation, trailing data, timeout and peer/type rejection\n";
}
