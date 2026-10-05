attribute vec2 aPosition;
varying vec2 vTex;
void main(){gl_Position=vec4(aPosition,0.0,1.0);vTex=(aPosition+1.0)*0.5;}
