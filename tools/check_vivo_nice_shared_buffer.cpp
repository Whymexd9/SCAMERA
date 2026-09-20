#include "vivo-nice-shared-buffer.h"
#include "vivo-nice-tce-buffer.h"
#include <cassert>
#include <vector>
using namespace vivo_nice;
static std::vector<int> events;
static int handle;static char pixels[64];static bool failAlloc=false,failStart=false,failEnd=false;
static size_t expectedSize=64;static int expectedCache=1;
void* create(){events.push_back(1);return &handle;}
int allocate(void** h,size_t n,int cache,int* fd,void** p){assert(*h==&handle&&n==expectedSize&&cache==expectedCache);events.push_back(2);*fd=17;*p=pixels;return failAlloc?-1:0;}
int release(void** h,int* fd,void** p){assert(*h==&handle&&*fd==17&&*p==pixels);events.push_back(5);*fd=-1;*p=nullptr;return 0;}
int destroy(void** h){assert(*h==&handle);events.push_back(6);*h=nullptr;return 0;}
int start(void* h,int fd,int mode,SharedBufferApi::SyncCallback cb,void* data){assert(h==&handle&&fd==17&&mode==3&&!cb&&!data);events.push_back(3);return failStart?-1:0;}
int end(void* h,int fd,int mode,SharedBufferApi::SyncCallback cb,void* data){assert(h==&handle&&fd==17&&mode==3&&!cb&&!data);events.push_back(4);return failEnd?-1:0;}
int main(){
 SharedBufferApi api{create,allocate,release,destroy,start,end};
 {SharedBuffer b(api,64,true);assert(b.fd()==17&&b.size()==64&&b.nativeAddress()==pixels);assert(b.beginCpuAccess()==pixels);b.endCpuAccess();}
 assert((events==std::vector<int>{1,2,3,4,5,6}));events.clear();
 {SharedBuffer b(api,64,true);b.beginCpuAccess();}
 assert((events==std::vector<int>{1,2,3,4,5,6}));events.clear();
 failAlloc=true;try{SharedBuffer b(api,64,true);assert(false);}catch(const std::runtime_error&){}
 assert((events==std::vector<int>{1,2,5,6}));events.clear();failAlloc=false;
 failStart=true;try{SharedBuffer b(api,64,true);b.beginCpuAccess();assert(false);}catch(const std::runtime_error&){}
 assert((events==std::vector<int>{1,2,3,5,6}));events.clear();failStart=false;
 {SharedBuffer b(api,64,true);b.beginCpuAccess();failEnd=true;try{b.endCpuAccess();assert(false);}catch(const std::runtime_error&){}failEnd=false;}
 assert((events==std::vector<int>{1,2,3,4,4,5,6}));
 events.clear();expectedSize=64*48*6;expectedCache=0;
 {TceRgbBuffer b(api,64,48);auto& d=b.image();
  assert(d.format==0x1004&&d.width==64&&d.height==48&&d.stride[0]==384&&d.scanline[0]==48);
  assert(d.data[0]==reinterpret_cast<uint64_t>(pixels)&&d.dataSize[0]==0&&d.nativeHandle==0);
  int fd;std::memcpy(&fd,d.unknown60.data(),4);assert(fd==17);
 }
 assert((events==std::vector<int>{1,2,5,6}));events.clear();
 try{TceRgbBuffer b(api,2147483647,2);assert(false);}catch(const std::invalid_argument&){}
 assert(events.empty());
}
