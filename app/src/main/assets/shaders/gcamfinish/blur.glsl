precision highp float;
precision highp sampler2D;
uniform sampler2D InputBuffer;
uniform vec2 outputSize;
out vec4 Output;
void main(){
    vec2 uv=gl_FragCoord.xy/outputSize;
    vec2 stepSize=1.0/vec2(textureSize(InputBuffer,0));
    vec4 total=vec4(0);
    for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){
        float w=float((x==0?2:1)*(y==0?2:1));
        total+=texture(InputBuffer,uv+vec2(x,y)*stepSize)*w;
    }
    Output=total/16.0;
}
