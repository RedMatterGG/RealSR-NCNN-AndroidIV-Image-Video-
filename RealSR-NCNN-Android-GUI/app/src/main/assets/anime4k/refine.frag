// Adapted from bloc97 Anime4K v0.9; MIT. See LICENSE-Anime4K.txt and NOTICE.md.
precision highp float;
varying vec2 vTex;
uniform sampler2D uTexture;
uniform sampler2D uAux;
uniform sampler2D uOriginal;
uniform vec2 uPt;
uniform float uStrength;



vec4 getAverage(vec4 cc, vec4 a, vec4 b, vec4 c) {
	return cc * (1.0 - uStrength) + ((a + b + c) / 3.0) * uStrength;
}

vec4 getRGBL(vec2 pos) {
    return vec4(texture2D(uTexture, pos).rgb, texture2D(uAux, pos).x);
}

float min3v(vec4 a, vec4 b, vec4 c) {
	return min(min(a.a, b.a), c.a);
}
float max3v(vec4 a, vec4 b, vec4 c) {
	return max(max(a.a, b.a), c.a);
}


vec4 reconstruct()  {
	vec2 d = uPt;
	
    vec4 cc = getRGBL(vTex);
	vec4 t = getRGBL(vTex + vec2(0.0, -d.y));
	vec4 tl = getRGBL(vTex + vec2(-d.x, -d.y));
	vec4 tr = getRGBL(vTex + vec2(d.x, -d.y));
	
	vec4 l = getRGBL(vTex + vec2(-d.x, 0.0));
	vec4 r = getRGBL(vTex + vec2(d.x, 0.0));
	
	vec4 b = getRGBL(vTex + vec2(0.0, d.y));
	vec4 bl = getRGBL(vTex + vec2(-d.x, d.y));
	vec4 br = getRGBL(vTex + vec2(d.x, d.y));
	
	//Kernel 0.0 and 4.0
	float maxDark = max3v(br, b, bl);
	float minLight = min3v(tl, t, tr);
	
	if (minLight > cc.a && minLight > maxDark) {
		return getAverage(cc, tl, t, tr);
	} else {
		maxDark = max3v(tl, t, tr);
		minLight = min3v(br, b, bl);
		if (minLight > cc.a && minLight > maxDark) {
			return getAverage(cc, br, b, bl);
		}
	}
	
	//Kernel 1.0 and 5.0
	maxDark = max3v(cc, l, b);
	minLight = min3v(r, t, tr);
	
	if (minLight > maxDark) {
		return getAverage(cc, r, t, tr);
	} else {
		maxDark = max3v(cc, r, t);
		minLight = min3v(bl, l, b);
		if (minLight > maxDark) {
			return getAverage(cc, bl, l, b);
		}
	}
	
	//Kernel 2.0 and 6.0
	maxDark = max3v(l, tl, bl);
	minLight = min3v(r, br, tr);
	
	if (minLight > cc.a && minLight > maxDark) {
		return getAverage(cc, r, br, tr);
	} else {
		maxDark = max3v(r, br, tr);
		minLight = min3v(l, tl, bl);
		if (minLight > cc.a && minLight > maxDark) {
			return getAverage(cc, l, tl, bl);
		}
	}
	
	//Kernel 3.0 and 7.0
	maxDark = max3v(cc, l, t);
	minLight = min3v(r, br, b);
	
	if (minLight > maxDark) {
		return getAverage(cc, r, br, b);
	} else {
		maxDark = max3v(cc, r, b);
		minLight = min3v(t, l, tl);
		if (minLight > maxDark) {
			return getAverage(cc, t, l, tl);
		}
	}
	
	
	return cc;
}
void main(){gl_FragColor=reconstruct();gl_FragColor.a=texture2D(uOriginal,vTex).a;}
