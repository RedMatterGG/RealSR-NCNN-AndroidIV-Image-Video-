// Adapted from bloc97 Anime4K v0.9; MIT. See LICENSE-Anime4K.txt and NOTICE.md.
precision highp float;
varying vec2 vTex;
uniform sampler2D uTexture;
uniform sampler2D uAux;
uniform sampler2D uOriginal;
uniform vec2 uPt;
uniform float uStrength;


vec4 getRGBL(vec2 pos) {
    vec3 c=texture2D(uTexture,pos).rgb; return vec4(c,(c.r+c.r+c.g+c.g+c.g+c.b)/6.0);
}

vec4 reconstruct() { //Save grad on POSTKERNEL
	vec2 d = uPt;
	
	//[tl  t tr]
	//[ l cc  r]
	//[bl  b br]
    vec4 cc = getRGBL(vTex);
	vec4 t = getRGBL(vTex + vec2(0.0, -d.y));
	vec4 tl = getRGBL(vTex + vec2(-d.x, -d.y));
	vec4 tr = getRGBL(vTex + vec2(d.x, -d.y));
	
	vec4 l = getRGBL(vTex + vec2(-d.x, 0.0));
	vec4 r = getRGBL(vTex + vec2(d.x, 0.0));
	
	vec4 b = getRGBL(vTex + vec2(0.0, d.y));
	vec4 bl = getRGBL(vTex + vec2(-d.x, d.y));
	vec4 br = getRGBL(vTex + vec2(d.x, d.y));
	
	
	//Horizontal Gradient
	//[-1.0  0.0  1.0]
	//[-2.0  0.0  2.0]
	//[-1.0  0.0  1.0]
	float xgrad = (-tl.a + tr.a - l.a - l.a + r.a + r.a - bl.a + br.a);
	
	//Vertical Gradient
	//[-1.0 -2.0 -1.0]
	//[ 0.0  0.0  0.0]
	//[ 1.0  2.0  1.0]
	float ygrad = (-tl.a - t.a - t.a - tr.a + bl.a + b.a + b.a + br.a);
	
	//Computes the luminance's gradient and saves it in the unused alpha channel
	return vec4(1.0 - clamp(sqrt(xgrad * xgrad + ygrad * ygrad), 0.0, 1.0));
}



void main(){gl_FragColor=reconstruct();}
