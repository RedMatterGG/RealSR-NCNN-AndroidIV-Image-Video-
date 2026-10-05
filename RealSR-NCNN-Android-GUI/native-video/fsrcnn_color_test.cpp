#include "engines/fsrcnn_color.h"
#include <fstream>
#include <vector>
int main(int argc,char** argv) {
 if(argc!=2)return 2;std::ifstream f(argv[1],std::ios::binary);
 std::vector<uint8_t> rgb(7*9*3),y(14*18),ycc(rgb.size()),out(14*18*3);
 f.read((char*)rgb.data(),rgb.size());f.read((char*)y.data(),y.size());if(!f)return 3;
 for(size_t i=0;i<rgb.size();i+=3)video::rgbYcc(rgb.data()+i,ycc.data()+i);
 for(int j=0;j<14;j++)for(int i=0;i<18;i++)video::yccRgb(y[j*18+i],video::chroma2x(ycc.data(),9,7,i,j,1),video::chroma2x(ycc.data(),9,7,i,j,2),out.data()+(j*18+i)*3);
 std::string p=argv[1];p=p.substr(0,p.find_last_of('.'))+".out";
 std::ofstream o(p,std::ios::binary);o.write((char*)ycc.data(),ycc.size());o.write((char*)out.data(),out.size());return !o;
}
