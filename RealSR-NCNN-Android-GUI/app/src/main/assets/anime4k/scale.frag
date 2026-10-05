// Adapted from bloc97 Anime4K v0.9; MIT. See LICENSE-Anime4K.txt and NOTICE.md.
precision highp float;
varying vec2 vTex;
uniform sampler2D uTexture;
uniform sampler2D uAux;
uniform sampler2D uOriginal;
uniform vec2 uPt;
uniform float uStrength;
void main(){
  vec4 c=texture2D(uTexture,vTex);
  gl_FragColor=vec4(c.rgb,(c.r+c.r+c.g+c.g+c.g+c.b)/6.0);
}
